/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.update.processor;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.solr.common.SolrDocumentBase;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrInputField;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.component.RealTimeGetComponent;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.schema.CopyField;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.schema.NumericValueFieldType;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.update.AddUpdateCommand;
import org.apache.solr.util.RefCounted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.solr.common.params.CommonParams.ID;

/**
 * @lucene.experimental
 */
public class AtomicUpdateDocumentMerger {
  
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  
  protected final IndexSchema schema;
  protected final SchemaField idField;
  
  public AtomicUpdateDocumentMerger(SolrQueryRequest queryReq) {
    schema = queryReq.getSchema();
    idField = schema.getUniqueKeyField();
  }
  
  /**
   * Utility method that examines the SolrInputDocument in an AddUpdateCommand
   * and returns true if the documents contains atomic update instructions.
   */
  public static boolean isAtomicUpdate(final AddUpdateCommand cmd) {
    SolrInputDocument sdoc = cmd.getSolrInputDocument();
    for (SolrInputField sif : sdoc.values()) {
      Object val = sif.getValue();
      if (val instanceof Map && !(val instanceof SolrDocumentBase)) {
        return true;
      }
    }
    
    return false;
  }
  
  /**
   * Merges the fromDoc into the toDoc using the atomic update syntax.
   * 
   * @param fromDoc SolrInputDocument which will merged into the toDoc
   * @param toDoc the final SolrInputDocument that will be mutated with the values from the fromDoc atomic commands
   * @return toDoc with mutated values
   */
  public SolrInputDocument merge(final SolrInputDocument fromDoc, SolrInputDocument toDoc) {
    for (SolrInputField sif : fromDoc.values()) {
     Object val = sif.getValue();
      if (val instanceof Map) {
        for (Entry<String,Object> entry : ((Map<String,Object>) val).entrySet()) {
          String key = entry.getKey();
          Object fieldVal = entry.getValue();
          boolean updateField = false;
          switch (key) {
            case "add":
              updateField = true;
              doAdd(toDoc, sif, fieldVal);
              break;
            case "set":
              updateField = true;
              doSet(toDoc, sif, fieldVal);
              break;
            case "remove":
              updateField = true;
              doRemove(toDoc, sif, fieldVal);
              break;
            case "removeregex":
              updateField = true;
              doRemoveRegex(toDoc, sif, fieldVal);
              break;
            case "inc":
              updateField = true;
              doInc(toDoc, sif, fieldVal);
              break;
            case "add-distinct":
              updateField = true;
              doAddDistinct(toDoc, sif, fieldVal);
              break;
            default:
              //Perhaps throw an error here instead?
              log.warn("Unknown operation for the an atomic update, operation ignored: " + key);
              break;
          }
          // validate that the field being modified is not the id field.
          if (updateField && idField.getName().equals(sif.getName())) {
            throw new SolrException(ErrorCode.BAD_REQUEST, "Invalid update of id field: " + sif);
          }

        }
      } else {
        // normal fields are treated as a "set"
        toDoc.put(sif.getName(), sif);
      }
    }
    
    return toDoc;
  }

  /**
   * Given a schema field, return whether or not such a field is supported for an in-place update.
   * Note: If an update command has updates to only supported fields (and _version_ is also supported),
   * only then is such an update command executed as an in-place update.
   */
  public static boolean isSupportedFieldForInPlaceUpdate(SchemaField schemaField) {
    return !(schemaField.indexed() || schemaField.stored() || !schemaField.hasDocValues() || 
        schemaField.multiValued() || !(schemaField.getType() instanceof NumericValueFieldType));
  }
  
  /**
   * Given an add update command, compute a list of fields that can be updated in-place. If there is even a single
   * field in the update that cannot be updated in-place, the entire update cannot be executed in-place (and empty set
   * will be returned in that case).
   * 
   * @return Return a set of fields that can be in-place updated.
   */
  public static Set<String> computeInPlaceUpdatableFields(AddUpdateCommand cmd) throws IOException {
    SolrInputDocument sdoc = cmd.getSolrInputDocument();
    IndexSchema schema = cmd.getReq().getSchema();
    
    final SchemaField uniqueKeyField = schema.getUniqueKeyField();
    final String uniqueKeyFieldName = null == uniqueKeyField ? null : uniqueKeyField.getName();

    final Set<String> candidateFields = new HashSet<>();

    // if _version_ field is not supported for in-place update, bail out early
    SchemaField versionField = schema.getFieldOrNull(CommonParams.VERSION_FIELD);
    if (versionField == null || !isSupportedFieldForInPlaceUpdate(versionField)) {
      return Collections.emptySet();
    }
    
    // first pass, check the things that are virtually free,
    // and bail out early if anything is obviously not a valid in-place update
    for (String fieldName : sdoc.getFieldNames()) {
      if (fieldName.equals(uniqueKeyFieldName)
          || fieldName.equals(CommonParams.VERSION_FIELD)) {
        continue;
      }
      Object fieldValue = sdoc.getField(fieldName).getValue();
      if (! (fieldValue instanceof Map) ) {
        // not an in-place update if there are fields that are not maps
        return Collections.emptySet();
      }
      // else it's a atomic update map...
      for (String op : ((Map<String, Object>)fieldValue).keySet()) {
        if (!op.equals("set") && !op.equals("inc")) {
          // not a supported in-place update op
          return Collections.emptySet();
        }
      }
      candidateFields.add(fieldName);
    }

    if (candidateFields.isEmpty()) {
      return Collections.emptySet();
    }

    // second pass over the candidates for in-place updates
    // this time more expensive checks involving schema/config settings
    for (String fieldName: candidateFields) {
      SchemaField schemaField = schema.getField(fieldName);

      if (!isSupportedFieldForInPlaceUpdate(schemaField)) {
        return Collections.emptySet();
      } 

      // if this field has copy target which is not supported for in place, then empty
      for (CopyField copyField: schema.getCopyFieldsList(fieldName)) {
        if (!isSupportedFieldForInPlaceUpdate(copyField.getDestination()))
          return Collections.emptySet();
      }
    }
    
    // third pass: requiring checks against the actual IndexWriter due to internal DV update limitations
    SolrCore core = cmd.getReq().getCore();
    RefCounted<IndexWriter> holder = core.getSolrCoreState().getIndexWriter(core);
    Set<String> fieldNamesFromIndexWriter = null;
    Set<String> segmentSortingFields = null;
    try {
      IndexWriter iw = holder.get();
      fieldNamesFromIndexWriter = iw.getFieldNames(); // This shouldn't be needed once LUCENE-7659 is resolved
      segmentSortingFields = iw.getConfig().getIndexSortFields();
    } finally {
      holder.decref();
    }
    for (String fieldName: candidateFields) {
      if (! fieldNamesFromIndexWriter.contains(fieldName) ) {
        return Collections.emptySet(); // if this field doesn't exist, DV update can't work
      }
      if (segmentSortingFields.contains(fieldName) ) {
        return Collections.emptySet(); // if this is used for segment sorting, DV updates can't work
      }
    }
    
    return candidateFields;
  }
  
  /**
   * Given an AddUpdateCommand containing update operations (e.g. set, inc), merge and resolve the operations into
   * a partial document that can be used for indexing the in-place updates. The AddUpdateCommand is modified to contain
   * the partial document (instead of the original document which contained the update operations) and also
   * the prevVersion that this in-place update depends on.
   * Note: updatedFields passed into the method can be changed, i.e. the version field can be added to the set.
   * @return If in-place update cannot succeed, e.g. if the old document is deleted recently, then false is returned. A false
   *        return indicates that this update can be re-tried as a full atomic update. Returns true if the in-place update
   *        succeeds.
   */
  public boolean doInPlaceUpdateMerge(AddUpdateCommand cmd, Set<String> updatedFields) throws IOException {
    SolrInputDocument inputDoc = cmd.getSolrInputDocument();
    BytesRef idBytes = cmd.getIndexedId();

    updatedFields.add(CommonParams.VERSION_FIELD); // add the version field so that it is fetched too
    SolrInputDocument oldDocument = RealTimeGetComponent.getInputDocument
      (cmd.getReq().getCore(), idBytes,
       null, // don't want the version to be returned
       true, // avoid stored fields from index
       updatedFields,
       true); // resolve the full document
                                              
    if (oldDocument == RealTimeGetComponent.DELETED || oldDocument == null) {
      // This doc was deleted recently. In-place update cannot work, hence a full atomic update should be tried.
      return false;
    }

    if (oldDocument.containsKey(CommonParams.VERSION_FIELD) == false) {
      throw new SolrException (ErrorCode.INVALID_STATE, "There is no _version_ in previous document. id=" + 
          cmd.getPrintableId());
    }
    Long oldVersion = (Long) oldDocument.remove(CommonParams.VERSION_FIELD).getValue();

    // If the oldDocument contains any other field apart from updatedFields (or id/version field), then remove them.
    // This can happen, despite requesting for these fields in the call to RTGC.getInputDocument, if the document was
    // fetched from the tlog and had all these fields (possibly because it was a full document ADD operation).
    if (updatedFields != null) {
      Collection<String> names = new HashSet<String>(oldDocument.getFieldNames());
      for (String fieldName: names) {
        if (fieldName.equals(CommonParams.VERSION_FIELD)==false && fieldName.equals(ID)==false && updatedFields.contains(fieldName)==false) {
          oldDocument.remove(fieldName);
        }
      }
    }
    // Copy over all supported DVs from oldDocument to partialDoc
    //
    // Assuming multiple updates to the same doc: field 'dv1' in one update, then field 'dv2' in a second
    // update, and then again 'dv1' in a third update (without commits in between), the last update would
    // fetch from the tlog the partial doc for the 2nd (dv2) update. If that doc doesn't copy over the
    // previous updates to dv1 as well, then a full resolution (by following previous pointers) would
    // need to be done to calculate the dv1 value -- so instead copy all the potentially affected DV fields.
    SolrInputDocument partialDoc = new SolrInputDocument();
    String uniqueKeyField = schema.getUniqueKeyField().getName();
    for (String fieldName : oldDocument.getFieldNames()) {
      SchemaField schemaField = schema.getField(fieldName);
      if (fieldName.equals(uniqueKeyField) || isSupportedFieldForInPlaceUpdate(schemaField)) {
        partialDoc.addField(fieldName, oldDocument.getFieldValue(fieldName));
      }
    }
    
    merge(inputDoc, partialDoc);

    // Populate the id field if not already populated (this can happen since stored fields were avoided during fetch from RTGC)
    if (!partialDoc.containsKey(schema.getUniqueKeyField().getName())) {
      partialDoc.addField(idField.getName(), 
          inputDoc.getField(schema.getUniqueKeyField().getName()).getFirstValue());
    }

    cmd.prevVersion = oldVersion;
    cmd.solrDoc = partialDoc;
    return true;
  }

  protected void doSet(SolrInputDocument toDoc, SolrInputField sif, Object fieldVal) {
    SchemaField sf = schema.getField(sif.getName());
    toDoc.setField(sif.getName(), sf.getType().toNativeType(fieldVal));
  }

  protected void doAdd(SolrInputDocument toDoc, SolrInputField sif, Object fieldVal) {
    SchemaField sf = schema.getField(sif.getName());
    toDoc.addField(sif.getName(), sf.getType().toNativeType(fieldVal));
  }

  protected void doAddDistinct(SolrInputDocument toDoc, SolrInputField sif, Object fieldVal) {
    final String name = sif.getName();
    SolrInputField existingField = toDoc.get(name);

    SchemaField sf = schema.getField(name);

    if (sf != null) {
      Collection<Object> original = existingField != null ?
          existingField.getValues() :
          new ArrayList<>();

      int initialSize = original.size();
      if (fieldVal instanceof Collection) {
        for (Object object : (Collection) fieldVal) {
          if (!original.contains(object)) {
            original.add(object);
          }
        }
      } else {
        Object object = sf.getType().toNativeType(fieldVal);
        if (!original.contains(object)) {
          original.add(object);
        }
      }

      if (original.size() > initialSize) { // update only if more are added
        if (original.size() == 1) { // if single value, pass the value instead of List
          doAdd(toDoc, sif, original.toArray()[0]);
        } else {
          toDoc.setField(name, original);
        }
      }
    }
  }

  protected void doInc(SolrInputDocument toDoc, SolrInputField sif, Object fieldVal) {
    SolrInputField numericField = toDoc.get(sif.getName());
    SchemaField sf = schema.getField(sif.getName());
    if (numericField != null || sf.getDefaultValue() != null) {
      // TODO: fieldtype needs externalToObject?
      String oldValS = (numericField != null) ?
          numericField.getFirstValue().toString(): sf.getDefaultValue().toString();
      BytesRefBuilder term = new BytesRefBuilder();
      sf.getType().readableToIndexed(oldValS, term);
      Object oldVal = sf.getType().toObject(sf, term.get());

      String fieldValS = fieldVal.toString();
      Number result;
      if (oldVal instanceof Long) {
        result = ((Long) oldVal).longValue() + Long.parseLong(fieldValS);
      } else if (oldVal instanceof Float) {
        result = ((Float) oldVal).floatValue() + Float.parseFloat(fieldValS);
      } else if (oldVal instanceof Double) {
        result = ((Double) oldVal).doubleValue() + Double.parseDouble(fieldValS);
      } else {
        // int, short, byte
        result = ((Integer) oldVal).intValue() + Integer.parseInt(fieldValS);
      }

      toDoc.setField(sif.getName(),  result);
    } else {
      toDoc.setField(sif.getName(), fieldVal);
    }
  }

  protected void doRemove(SolrInputDocument toDoc, SolrInputField sif, Object fieldVal) {
    final String name = sif.getName();
    SolrInputField existingField = toDoc.get(name);
    if (existingField == null) return;
    SchemaField sf = schema.getField(name);

    if (sf != null) {
      final Collection<Object> original = existingField.getValues();
      if (fieldVal instanceof Collection) {
        for (Object object : (Collection) fieldVal) {
          Object o = sf.getType().toNativeType(object);
          original.remove(o);
        }
      } else {
        original.remove(sf.getType().toNativeType(fieldVal));
      }

      toDoc.setField(name, original);
    }
  }

  protected void doRemoveRegex(SolrInputDocument toDoc, SolrInputField sif, Object valuePatterns) {
    final String name = sif.getName();
    final SolrInputField existingField = toDoc.get(name);
    if (existingField != null) {
      final Collection<Object> valueToRemove = new HashSet<>();
      final Collection<Object> original = existingField.getValues();
      final Collection<Pattern> patterns = preparePatterns(valuePatterns);
      for (Object value : original) {
        for(Pattern pattern : patterns) {
          final Matcher m = pattern.matcher(value.toString());
          if (m.matches()) {
            valueToRemove.add(value);
          }
        }
      }
      original.removeAll(valueToRemove);
      toDoc.setField(name, original);
    }
  }

  private Collection<Pattern> preparePatterns(Object fieldVal) {
    final Collection<Pattern> patterns = new LinkedHashSet<>(1);
    if (fieldVal instanceof Collection) {
      Collection<String> patternVals = (Collection<String>) fieldVal;
      for (String patternVal : patternVals) {
        patterns.add(Pattern.compile(patternVal));
      }
    } else {
      patterns.add(Pattern.compile(fieldVal.toString()));
    }
    return patterns;
  }
  
}
