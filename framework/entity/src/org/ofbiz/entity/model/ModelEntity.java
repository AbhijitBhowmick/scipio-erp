/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.ofbiz.entity.model;

import java.io.PrintWriter;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.GeneralException;
import org.ofbiz.base.util.ObjectType;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilPlist;
import org.ofbiz.base.util.UtilTimer;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.base.util.UtilXml;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntity;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.config.model.Datasource;
import org.ofbiz.entity.config.model.EntityConfig;
import org.ofbiz.entity.jdbc.DatabaseUtil;
import org.ofbiz.entity.model.ModelIndex.Field;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * An object that models the <code>&lt;entity&gt;</code> element.
 *
 */
@SuppressWarnings("serial")
public class ModelEntity implements Comparable<ModelEntity>, Serializable {

    private static final Debug.OfbizLogger module = Debug.getOfbizLogger(java.lang.invoke.MethodHandles.lookup().lookupClass());

    /** The name of the time stamp field for locking/synchronization */
    public static final String STAMP_FIELD = "lastUpdatedStamp";
    public static final String STAMP_TX_FIELD = "lastUpdatedTxStamp";
    public static final String CREATE_STAMP_FIELD = "createdStamp";
    public static final String CREATE_STAMP_TX_FIELD = "createdTxStamp";
    public static final List<String> STAMP_FIELD_LIST = UtilMisc.unmodifiableArrayList(STAMP_FIELD, STAMP_TX_FIELD, CREATE_STAMP_FIELD, CREATE_STAMP_TX_FIELD); // SCIPIO

    /** SCIPIO: Standard entity field for JSON data storage (2019: added for 2.1.0) */
    public static final String ENTITY_JSON_FIELD = "entityJson";

    private ModelInfo modelInfo;

    /** The ModelReader that created this Entity */
    private final ModelReader modelReader;

    /** The entity-name of the Entity */
    protected String entityName = "";

    /** The table-name of the Entity */
    protected String tableName = "";

    /** The package-name of the Entity */
    protected String packageName = "";

    /** The entity-name of the Entity that this Entity is dependent on, if empty then no dependency */
    protected String dependentOn = "";

    /** The sequence-bank-size of the Entity */
    protected Integer sequenceBankSize = null;

    /** Synchronization object used to control access to the ModelField collection objects.
     * A single lock is used for all ModelField collections so collection updates are atomic. */
    // SCIPIO: 2018-09-07: fixed: using new Object() is invalid because Object is not Serializable
    //private final Object fieldsLock = new Object();
    private final Object fieldsLock = new Serializable() {};

    /**
     * SCIPIO: Nested class to encapsulate related fields information, to ensure
     * atomicity and especially remove need for synchronized blocks on ModelEntity getters.
     * <p>
     * This is used to modify ModelEntity with a <code>fields</code> member.
     * <p>
     * <strong>WARNING:</strong> All members of this class are READ-ONLY and
     * must NEVER be edited in-place! Every change requires collection copies AND new instance!
     * <p>
     * The old stock field collection members directly on ModelEntity were edited in-place
     * even if marked final! This is no longer acceptable. Such design forced the getters
     * to have detrimental synchronized blocks (now removed). Only the setters, addField,
     * removeField and addExtendEntity now still need synchronized blocks.
     * <p>
     * Added 2018-09-29.
     */
    private class Fields implements Serializable {
        /** NOTE: LinkedHashMap now preserves field order in map (SCIPIO). */
        private final Map<String, ModelField> fieldsMap;

        /** Model fields in the order they were defined. This list duplicates the values in fieldsMap, but
         *  we must keep the list in its original sequence for SQL DISTINCT operations to work properly. */
        private final List<ModelField> fieldsList;

        private final List<String> fieldNames;

        /** A List of the Field objects for the Entity, one for each Primary Key */
        private final List<ModelField> pks;

        private final List<String> pkFieldNames;

        /** A List of the Field objects for the Entity, one for each NON Primary Key */
        private final List<ModelField> noPks;

        private final List<String> noPkFieldNames;

        protected final List<ModelField> selectableFieldsList;

        /**
         * Main constructor. (Re)creates the fields info using a linked fields map - always pass LinkedHashMap.
         * NOTE: The pk fields order must be explicitly passed because in rare (problem?) cases prim-key order on
         * entities are different than field order, so not respecting breaks compatibility; if null same order is assumed.
         */
        protected Fields(Map<String, ModelField> fieldsMap, List<String> pkFieldNamesOrig) {
            ArrayList<ModelField> fieldsList = new ArrayList<>(fieldsMap.size());
            ArrayList<String> fieldNames = new ArrayList<>(fieldsMap.size());
            ArrayList<ModelField> pks = new ArrayList<>(fieldsMap.size());
            ArrayList<String> pkFieldNames = (pkFieldNamesOrig != null) ? new ArrayList<>(pkFieldNamesOrig) : new ArrayList<>(fieldsMap.size());
            ArrayList<ModelField> nopks = new ArrayList<>(fieldsMap.size());
            ArrayList<String> nonPkFieldNames = new ArrayList<>(fieldsMap.size());
            ArrayList<ModelField> selectableFieldsList = new ArrayList<>(fieldsMap.size());
            for(ModelField field : fieldsMap.values()) {
                fieldsList.add(field);
                fieldNames.add(field.getName());
                if (!field.getIsPk()) {
                    nopks.add(field);
                    nonPkFieldNames.add(field.getName());
                } else if (pkFieldNamesOrig == null) {
                    pks.add(field);
                    pkFieldNames.add(field.getName());
                }
                if (!Boolean.FALSE.equals(field.getSelect())) {
                    selectableFieldsList.add(field);
                }
            }
            if (pkFieldNamesOrig != null) {
                // Must be done last to preserve pk field sequence
                for (String pkFieldName : pkFieldNames) {
                    ModelField pkField = fieldsMap.get(pkFieldName);
                    if (pkField == null) {
                        Debug.logError("Error in entity definition - primary key is invalid for entity [" + getEntityName() + "]: " +
                                "primary key [" + pkFieldName + "] name does not reference any entity field name", module); // SCIPIO: now error, better message
                    } else {
                        pks.add(pkField);
                    }
                }
            }
            fieldsList.trimToSize();
            fieldNames.trimToSize();
            pks.trimToSize();
            pkFieldNames.trimToSize();
            nopks.trimToSize();
            nonPkFieldNames.trimToSize();
            selectableFieldsList.trimToSize();
            this.fieldsList = Collections.unmodifiableList(fieldsList);
            this.fieldsMap = Collections.unmodifiableMap(fieldsMap);
            this.fieldNames = Collections.unmodifiableList(fieldNames);
            this.pks = Collections.unmodifiableList(pks);
            this.pkFieldNames = Collections.unmodifiableList(pkFieldNames);
            this.noPks = Collections.unmodifiableList(nopks);
            this.noPkFieldNames = Collections.unmodifiableList(nonPkFieldNames);
            this.selectableFieldsList = Collections.unmodifiableList(selectableFieldsList);
        }

        /*
        public static Fields from(List<ModelField> fieldsList) {
            Map<String, ModelField> fieldsMap = new LinkedHashMap<>();
            for(ModelField field : fieldsList) {
                fieldsMap.put(field.getName(), field);
            }
            return new Fields(fieldsMap);
        }
         */

        private Fields() {
            this.fieldsList = Collections.emptyList();
            this.fieldsMap = Collections.emptyMap();
            this.fieldNames = Collections.emptyList();
            this.pks = Collections.emptyList();
            this.pkFieldNames = Collections.emptyList();
            this.noPks = Collections.emptyList();
            this.noPkFieldNames = Collections.emptyList();
            this.selectableFieldsList = Collections.emptyList();
        }

        public Fields add(ModelField newField) {
            Map<String, ModelField> fieldsMap = new LinkedHashMap<>(this.fieldsMap);
            List<String> pkFieldNames = new ArrayList<>(this.pkFieldNames);
            add(newField, fieldsMap, pkFieldNames);
            return new Fields(fieldsMap, pkFieldNames);
        }

        public Fields add(Collection<ModelField> newFields) {
            Map<String, ModelField> fieldsMap = new LinkedHashMap<>(this.fieldsMap);
            List<String> pkFieldNames = new ArrayList<>(this.pkFieldNames);
            for(ModelField newField : newFields) {
                add(newField, fieldsMap, pkFieldNames);
            }
            return new Fields(fieldsMap, pkFieldNames);
        }

        private void add(ModelField newField, Map<String, ModelField> fieldsMap, List<String> pkFieldNames) {
            ModelField oldField = fieldsMap.remove(newField.getName()); // NOTE: to preserve legacy behavior with the lists we do removes on the LinkedHashMap first
            if (oldField != null) {
                Debug.logWarning("Duplicate field definition in entity [" + entityName + "] for field [" + newField.getName() + "]; replacing with last added", module);
            }
            fieldsMap.put(newField.getName(), newField);
            if (newField.getIsPk()) {
                if (!pkFieldNames.contains(newField.getName())) { // NOTE: contains check was from stock - never change the pk order
                    pkFieldNames.add(newField.getName());
                }
            }
        }

        public Fields remove(String fieldName) {
            Map<String, ModelField> fieldsMap = new LinkedHashMap<>(this.fieldsMap);
            List<String> pkFieldNames = new ArrayList<>(this.pkFieldNames);
            fieldsMap.remove(fieldName);
            pkFieldNames.remove(fieldName);
            return new Fields(fieldsMap, pkFieldNames);
        }
    }

    /**
     * SCIPIO: Entity fields information (atomic).
     * <p>
     * TODO: REVIEW: Do we really need this volatile? Post-initialization it probably
     * doesn't do much due to the volatile already on the ModelReader entityCache.
     * However, to be safe and to ensure no issues during initialization, am leaving
     * volatile for time being.
     * <p>
     * Added 2018-09-29.
     */
    private volatile Fields fields;

    /** relations defining relationships between this entity and other entities */
    protected CopyOnWriteArrayList<ModelRelation> relations = new CopyOnWriteArrayList<>();

    /** indexes on fields/columns in this entity */
    private CopyOnWriteArrayList<ModelIndex> indexes = new CopyOnWriteArrayList<>();

    /** The reference of the dependentOn entity model */
    protected ModelEntity specializationOfModelEntity = null;

    /** The list of entities that are specialization of on this entity */
    protected Map<String, ModelEntity> specializedEntities = new LinkedHashMap<>();

    /**
     * map of ModelViewEntities that references this model
     * <p>
     * SCIPIO: 2018-09-29: <strong>WARNING:</strong> This variable is now READ-ONLY;
     * copies are made on modifications. This removes the need for synchronized blocks
     * on the getters. Same as {@link #fields}.
     * <p>
     * TODO: REVIEW: Could potentially remove volatile and use unmodifiableSet(HashSet) alone.
     * See discussion above.
     */
    //private final Set<String> viewEntities = new HashSet<>(); // SCIPIO: now read-only
    private volatile Set<String> viewEntities = Collections.emptySet();

    /** An indicator to specify if this entity requires locking for updates */
    protected boolean doLock = false;

    /** Can be used to disable automatically creating update stamp fields and populating them on inserts and updates */
    protected boolean noAutoStamp = false;

    /** An indicator to specify if this entity is never cached.
     * If true causes the delegator to not clear caches on write and to not get
     * from cache on read showing a warning messages to that effect
     */
    protected boolean neverCache = false;

    protected boolean neverCheck = false;

    protected boolean autoClearCache = true;

    /** The location of this entity's definition */
    protected String location = "";

    protected Boolean aliasColumns; // SCIPIO

    // ===== CONSTRUCTORS =====
    /** Default Constructor */
    public ModelEntity() {
        this.modelReader = null;
        this.modelInfo = ModelInfo.DEFAULT;
        this.fields = new Fields(); // SCIPIO: 2018-09-29
    }

    protected ModelEntity(ModelReader reader) {
        this.modelReader = reader;
        this.modelInfo = ModelInfo.DEFAULT;
        this.fields = new Fields(); // SCIPIO: 2018-09-29
    }

    protected ModelEntity(ModelReader reader, ModelInfo modelInfo) {
        this.modelReader = reader;
        this.modelInfo = modelInfo;
        this.fields = new Fields(); // SCIPIO: 2018-09-29
    }

    /** XML Constructor */
    protected ModelEntity(ModelReader reader, Element entityElement, ModelInfo modelInfo) {
        this.modelReader = reader;
        this.modelInfo = ModelInfo.createFromAttributes(modelInfo, entityElement);
        this.fields = new Fields(); // SCIPIO: 2018-09-29
    }

    /** XML Constructor */
    public ModelEntity(ModelReader reader, Element entityElement, UtilTimer utilTimer, ModelInfo modelInfo) {
        this.modelReader = reader;
        this.modelInfo = ModelInfo.createFromAttributes(modelInfo, entityElement);

        // SCIPIO: 2018-09-29: Create local instances of fields collections
        Map<String, ModelField> fieldsMap = new LinkedHashMap<>();
        ArrayList<String> pkFieldNames = new ArrayList<>();

        if (utilTimer != null) utilTimer.timerString("  createModelEntity: before general/basic info");
        this.populateBasicInfo(entityElement);
        if (utilTimer != null) utilTimer.timerString("  createModelEntity: before prim-keys");
        for (Element pkElement: UtilXml.childElementList(entityElement, "prim-key")) {
            pkFieldNames.add(pkElement.getAttribute("field").intern());
        }
        if (utilTimer != null) utilTimer.timerString("  createModelEntity: before fields");
        for (Element fieldElement: UtilXml.childElementList(entityElement, "field")) {
            String fieldName = UtilXml.checkEmpty(fieldElement.getAttribute("name")).intern();
            boolean isPk = pkFieldNames.contains(fieldName);
            ModelField field = ModelField.create(this, fieldElement, isPk);
            // SCIPIO: 2018-09-29: Now using new helper, to NOT edit instance collections in place
            //internalAddField(field, pkFieldNames);
            fieldsMap.put(field.getName(), field);
        }
        // if applicable automatically add the STAMP_FIELD and STAMP_TX_FIELD fields
        if ((this.doLock || !this.noAutoStamp) && !fieldsMap.containsKey(STAMP_FIELD)) {
            ModelField newField = ModelField.create(this, "", STAMP_FIELD, "date-time", null, null, null, false, false, false, true, false, null);
            // SCIPIO: 2018-09-29: Now using new helper, to NOT edit instance collections in place
            //internalAddField(newField, pkFieldNames);
            fieldsMap.put(newField.getName(), newField);
        }
        if (!this.noAutoStamp && !fieldsMap.containsKey(STAMP_TX_FIELD)) {
            ModelField newField = ModelField.create(this, "", STAMP_TX_FIELD, "date-time", null, null, null, false, false, false, true, false, null);
            // SCIPIO: 2018-09-29: Now using new helper, to NOT edit instance collections in place
            //internalAddField(newField, pkFieldNames);
            fieldsMap.put(newField.getName(), newField);
            // also add an index for this field
            String indexName = ModelUtil.shortenDbName(this.tableName + "_TXSTMP", 18);
            Field indexField = new Field(STAMP_TX_FIELD, null);
            ModelIndex txIndex = ModelIndex.create(this, null, indexName, UtilMisc.toList(indexField), false);
            indexes.add(txIndex);
        }
        // if applicable automatically add the CREATE_STAMP_FIELD and CREATE_STAMP_TX_FIELD fields
        if ((this.doLock || !this.noAutoStamp) && !fieldsMap.containsKey(CREATE_STAMP_FIELD)) {
            ModelField newField = ModelField.create(this, "", CREATE_STAMP_FIELD, "date-time", null, null, null, false, false, false, true, false, null);
            // SCIPIO: 2018-09-29: Now using new helper, to NOT edit instance collections in place
            //internalAddField(newField, pkFieldNames);
            fieldsMap.put(newField.getName(), newField);
        }
        if (!this.noAutoStamp && !fieldsMap.containsKey(CREATE_STAMP_TX_FIELD)) {
            ModelField newField = ModelField.create(this, "", CREATE_STAMP_TX_FIELD, "date-time", null, null, null, false, false, false, true, false, null);
            // SCIPIO: 2018-09-29: Now using new helper, to NOT edit instance collections in place
            //internalAddField(newField, pkFieldNames);
            fieldsMap.put(newField.getName(), newField);
            // also add an index for this field
            String indexName = ModelUtil.shortenDbName(this.tableName + "_TXCRTS", 18);
            Field indexField = new Field(CREATE_STAMP_TX_FIELD, null);
            ModelIndex txIndex = ModelIndex.create(this, null, indexName, UtilMisc.toList(indexField), false);
            indexes.add(txIndex);
        }
        reader.incrementFieldCount(fieldsMap.size());

        // SCIPIO: Update member with copies for change atomicity
        this.fields = new Fields(fieldsMap, pkFieldNames);

        if (utilTimer != null) utilTimer.timerString("  createModelEntity: before relations");
        this.populateRelated(reader, entityElement);
        this.populateIndexes(entityElement);

        this.aliasColumns = UtilMisc.booleanValue(entityElement.getAttribute("alias-columns"));
    }

    /** DB Names Constructor */
    public ModelEntity(String tableName, Map<String, DatabaseUtil.ColumnCheckInfo> colMap, ModelFieldTypeReader modelFieldTypeReader, boolean isCaseSensitive) {
        // if there is a dot in the name, remove it and everything before it, should be the schema name
        this.modelReader = null;
        this.modelInfo = ModelInfo.DEFAULT;
        this.tableName = tableName;
        int dotIndex = this.tableName.indexOf('.');
        if (dotIndex >= 0) {
            this.tableName = this.tableName.substring(dotIndex + 1);
        }
        this.entityName = ModelUtil.dbNameToClassName(this.tableName);

        // SCIPIO: 2018-09-29: Create local instances of fields collections
        Map<String, ModelField> fieldsMap = new LinkedHashMap<>();
        for (Map.Entry<String, DatabaseUtil.ColumnCheckInfo> columnEntry : colMap.entrySet()) {
            DatabaseUtil.ColumnCheckInfo ccInfo = columnEntry.getValue();
            ModelField newField = ModelField.create(this, ccInfo, modelFieldTypeReader);
            // SCIPIO: 2018-09-29: Now using new helper, to NOT edit instance collections in place
            //addField(newField);
            fieldsMap.put(newField.getName(), newField);
        }
        // SCIPIO: TODO: REVIEW: unlike XML constructor this does not make an effort to preserve pkFieldsNames order, could be a problem somewhere?
        this.fields = new Fields(fieldsMap, null); // SCIPIO: Update member with copies for change atomicity
    }

    protected void populateBasicInfo(Element entityElement) {
        this.entityName = UtilXml.checkEmpty(entityElement.getAttribute("entity-name")).intern();
        this.tableName = UtilXml.checkEmpty(entityElement.getAttribute("table-name"), ModelUtil.javaNameToDbName(this.entityName)).intern();
        this.packageName = UtilXml.checkEmpty(entityElement.getAttribute("package-name")).intern();
        this.dependentOn = UtilXml.checkEmpty(entityElement.getAttribute("dependent-on")).intern();
        this.doLock = UtilXml.checkBoolean(entityElement.getAttribute("enable-lock"), false);
        this.noAutoStamp = UtilXml.checkBoolean(entityElement.getAttribute("no-auto-stamp"), false);
        this.neverCache = UtilXml.checkBoolean(entityElement.getAttribute("never-cache"), false);
        this.neverCheck = UtilXml.checkBoolean(entityElement.getAttribute("never-check"), false);
        this.autoClearCache = UtilXml.checkBoolean(entityElement.getAttribute("auto-clear-cache"), true);

        String sequenceBankSizeStr = UtilXml.checkEmpty(entityElement.getAttribute("sequence-bank-size"));
        if (UtilValidate.isNotEmpty(sequenceBankSizeStr)) {
            try {
                this.sequenceBankSize = Integer.valueOf(sequenceBankSizeStr);
            } catch (NumberFormatException e) {
                Debug.logError("Error parsing sequence-bank-size value [" + sequenceBankSizeStr + "] for entity [" + this.entityName + "]", module);
            }
        }
    }

    /* SCIPIO: 2018-09-29: Now using new helper, to NOT edit instance collections in place
    private void internalAddField(ModelField newField, List<String> pkFieldNames) {
        if (!newField.getIsPk()) {
            this.nopks.add(newField);
        }
        this.fieldsList.add(newField);
        this.fieldsMap.put(newField.getName(), newField);
    }
    */

    protected void populateRelated(ModelReader reader, Element entityElement) {
        List<ModelRelation> tempList = new ArrayList<>(this.relations);
        for (Element relationElement: UtilXml.childElementList(entityElement, "relation")) {
            ModelRelation relation = reader.createRelation(this, relationElement);
            if (relation != null) {
                tempList.add(relation);
            }
        }
        this.relations = new CopyOnWriteArrayList<>(tempList);
    }


    protected void populateIndexes(Element entityElement) {
        List<ModelIndex> tempList = new ArrayList<>(this.indexes);
        for (Element indexElement: UtilXml.childElementList(entityElement, "index")) {
            ModelIndex index = ModelIndex.create(this, indexElement);
            tempList.add(index);
        }
        this.indexes = new CopyOnWriteArrayList<>(tempList);
    }

    public boolean containsAllPkFieldNames(Set<String> fieldNames) {
        Iterator<ModelField> pksIter = this.getPksIterator();
        while (pksIter.hasNext()) {
            ModelField pkField = pksIter.next();
            if (!fieldNames.contains(pkField.getName())) {
                return false;
            }
        }
        return true;
    }

    /**
     * addExtendEntity.
     * <p>
     * SCIPIO: <strong>WARNING:</strong> 2018-09-29: DO NOT CALL THIS METHOD FROM CLIENT CODE
     * OR CLIENT FRAMEWORK PATCHES!
     * This stock method should never have existed as a public method and if called prior
     * to entity engine loading will causing unpredictable results!
     * This method may be removed entirely in Scipio soon.
     */
    public void addExtendEntity(ModelReader reader, Element extendEntityElement) {
        if (extendEntityElement.hasAttribute("enable-lock")) {
            this.doLock = UtilXml.checkBoolean(extendEntityElement.getAttribute("enable-lock"), false);
        }

        if (extendEntityElement.hasAttribute("no-auto-stamp")) {
            this.noAutoStamp = UtilXml.checkBoolean(extendEntityElement.getAttribute("no-auto-stamp"), false);
        }

        if (extendEntityElement.hasAttribute("auto-clear-cache")) {
            this.autoClearCache = UtilXml.checkBoolean(extendEntityElement.getAttribute("auto-clear-cache"), false);
        }

        if (extendEntityElement.hasAttribute("never-cache")) {
            this.neverCache = UtilXml.checkBoolean(extendEntityElement.getAttribute("never-cache"), false);
        }

        if (extendEntityElement.hasAttribute("sequence-bank-size")) {
            String sequenceBankSizeStr = UtilXml.checkEmpty(extendEntityElement.getAttribute("sequence-bank-size"));
            if (UtilValidate.isNotEmpty(sequenceBankSizeStr)) {
                try {
                    this.sequenceBankSize = Integer.valueOf(sequenceBankSizeStr);
                } catch (NumberFormatException e) {
                    Debug.logError("Error parsing sequence-bank-size value [" + sequenceBankSizeStr + "] for entity [" + this.entityName + "]", module);
                }
            }
        }

        // SCIPIO: 2018-09-29: Moved synchronized block outside the loop (was stock logic bug)
        // add to the entity as a new field
        synchronized (fieldsLock) {
            // SCIPIO: Create copies for change atomicity
            Map<String, ModelField> fieldsMap = new LinkedHashMap<>(this.fields.fieldsMap);
            List<String> pkFieldNames = new ArrayList<>(this.fields.pkFieldNames);
            for (Element fieldElement : UtilXml.childElementList(extendEntityElement, "field")) {
                ModelField newField = ModelField.create(this, fieldElement, false);
                ModelField existingField = this.getField(newField.getName());
                if (existingField != null) {
                    // override the existing field's attributes
                    // TODO: only overrides of type, colName, description and enable-audit-log are currently supported
                    String type = existingField.getType();
                    if (!newField.getType().isEmpty()) {
                        type = newField.getType();
                    }
                    String colName = existingField.getColName();
                    if (!newField.getColName().isEmpty()) {
                        colName = newField.getColName();
                    }
                    String description = existingField.getDescription();
                    if (!newField.getDescription().isEmpty()) {
                        description = newField.getDescription();
                    }
                    boolean enableAuditLog = existingField.getEnableAuditLog();
                    if (UtilValidate.isNotEmpty(fieldElement.getAttribute("enable-audit-log"))) {
                        enableAuditLog = "true".equals(fieldElement.getAttribute("enable-audit-log"));
                    }
                    newField = ModelField.create(this, description, existingField.getName(), type, colName, existingField.getColValue(), existingField.getFieldSet(),
                            existingField.getIsNotNull(), existingField.getIsPk(), existingField.getEncryptMethod(), existingField.getIsAutoCreatedInternal(),
                            enableAuditLog, existingField.getValidators());
                }
                //synchronized (fieldsLock) { // SCIPIO: 2018-09-29: Moved synchronized block outside the loop (was stock logic bug)
                /*
                if (existingField != null) {
                    fieldsList.remove(existingField);
                }
                fieldsList.add(newField);
                 */
                // SCIPIO: TODO: REVIEW: using a LinkedHashMap we have to do a remove before an add to emulate the previous list logic,
                //          however the logic itself was doubtful (never happens?)...
                if (existingField != null) {
                    fieldsMap.remove(existingField.getName());
                }
                fieldsMap.put(newField.getName(), newField);
                if (newField.getIsPk()) {
                    if (existingField != null) {
                        pkFieldNames.remove(existingField.getName());
                    }
                    pkFieldNames.add(existingField.getName());
                }
                /* SCIPIO: redundant
                if (!newField.getIsPk()) {
                    if (existingField != null) {
                        nopks.remove(existingField);
                    }
                    nopks.add(newField);
                } else {
                    if (existingField != null) {
                        pks.remove(existingField);
                    }
                    pks.add(newField);
                    if (!pkFieldNames.contains(newField.getName())) {
                        pkFieldNames.add(newField.getName());
                    }
                }
                */
                //}
            }

            // SCIPIO: Update member with copies for change atomicity
            this.fields = new Fields(fieldsMap, pkFieldNames);
        }
        this.modelInfo = ModelInfo.createFromAttributes(this.modelInfo, extendEntityElement);
        this.populateRelated(reader, extendEntityElement);
        this.populateIndexes(extendEntityElement);
        this.dependentOn = UtilXml.checkEmpty(extendEntityElement.getAttribute("dependent-on")).intern();
    }

    // ===== GETTERS/SETTERS =====


    public ModelReader getModelReader() {
        return modelReader;
    }

    /** The entity-name of the Entity */
    public String getEntityName() {
        return this.entityName;
    }

    public void setEntityName(String entityName) {
        this.entityName = entityName;
    }

    /** The plain table-name of the Entity without a schema name prefix */
    public String getPlainTableName() {
        return this.tableName;
    }

    /** The table-name of the Entity including a Schema name if specified in the datasource config */
    public String getTableName(String helperName) {
        return getTableName(EntityConfig.getDatasource(helperName));
    }

    /** The table-name of the Entity including a Schema name if specified in the datasource config */
    public String getTableName(Datasource datasourceInfo) {
        if (datasourceInfo != null && UtilValidate.isNotEmpty(datasourceInfo.getSchemaName())) {
            return datasourceInfo.getSchemaName() + "." + this.tableName;
        } else {
            return this.tableName;
        }
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    /** The package-name of the Entity */
    public String getPackageName() {
        return this.packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    /** The entity-name of the Entity that this Entity is dependent on, if empty then no dependency */
    public String getDependentOn() {
        return this.dependentOn;
    }

    public void setDependentOn(String dependentOn) {
        this.dependentOn = dependentOn;
    }

    /** An indicator to specify if this entity is never cached.
     * If true causes the delegator to not clear caches on write and to not get
     * from cache on read showing a warning messages to that effect
     */
    public boolean getNeverCache() {
        return this.neverCache;
    }

    public void setNeverCache(boolean neverCache) {
        this.neverCache = neverCache;
    }

    /**
     * An indicator to specific if this entity should ignore automatic DB checks.
     * This should be set when the entity is mapped to a database view to prevent
     * warnings and attempts to modify the schema.
     */
    public boolean getNeverCheck() {
        return neverCheck;
    }

    public void setNeverCheck(boolean neverCheck) {
        this.neverCheck = neverCheck;
    }

    public boolean getAutoClearCache() {
        return this.autoClearCache;
    }

    public void setAutoClearCache(boolean autoClearCache) {
        this.autoClearCache = autoClearCache;
    }

    public boolean getHasFieldWithAuditLog() {
        for (ModelField mf : getFields()) {
            if (mf.getEnableAuditLog()) {
                return true;
            }
        }
        return false;
    }

    /* Get the location of this entity's definition */
    public String getLocation() {
        return this.location;
    }

    /* Set the location of this entity's definition */
    public void setLocation(String location) {
        this.location = location;
    }

    /** An indicator to specify if this entity requires locking for updates */
    public boolean getDoLock() {
        return this.doLock;
    }

    public void setDoLock(boolean doLock) {
        this.doLock = doLock;
    }

    public boolean lock() {
        if (doLock && isField(STAMP_FIELD)) {
            return true;
        } else {
            doLock = false;
            return false;
        }
    }

    public Integer getSequenceBankSize() {
        return this.sequenceBankSize;
    }

    public boolean isField(String fieldName) {
        if (fieldName == null) return false;
        //synchronized (fieldsLock) { // SCIPIO: 2018-09-29: Removed detrimental sync block for getters
        return fields.fieldsMap.containsKey(fieldName); // SCIPIO: 2018-09-29: fields member
        //}
    }

    public boolean areFields(Collection<String> fieldNames) {
        if (fieldNames == null) return false;
        for (String fieldName: fieldNames) {
            if (!isField(fieldName)) return false;
        }
        return true;
    }

    public boolean isPkField(String fieldName) { // SCIPIO
        if (fieldName == null) return false;
        return fields.pkFieldNames.contains(fieldName);
    }

    public boolean arePkFields(Collection<String> fieldNames) { // SCIPIO
        if (fieldNames == null) return false;
        for (String fieldName: fieldNames) {
            if (!isPkField(fieldName)) return false;
        }
        return true;
    }

    public boolean isNoPkField(String fieldName) { // SCIPIO
        if (fieldName == null) return false;
        return fields.noPkFieldNames.contains(fieldName);
    }

    public boolean areNoPkField(Collection<String> fieldNames) { // SCIPIO
        if (fieldNames == null) return false;
        for (String fieldName: fieldNames) {
            if (!isNoPkField(fieldName)) return false;
        }
        return true;
    }

    public int getPksSize() {
        //synchronized (fieldsLock) { // SCIPIO: 2018-09-29: Removed detrimental sync block for getters
        return this.fields.pks.size(); // SCIPIO: 2018-09-29: fields member
        //}
    }

    public boolean isSinglePk() { // SCIPIO
        return getPksSize() == 1;
    }

    public ModelField getOnlyPk() {
        //synchronized (fieldsLock) { // SCIPIO: 2018-09-29: Removed detrimental sync block for getters
        // SCIPIO: 2018-09-29: Use local var to avoid potential sync issues from consecutive instance reads
        //if (this.pks.size() == 1) {
        //    return this.pks.get(0);
        List<ModelField> pks = this.fields.pks; // SCIPIO: 2018-09-29: fields member
        if (pks.size() == 1) {
            return pks.get(0);
        } else {
            throw new IllegalArgumentException("Error in getOnlyPk, the [" + this.getEntityName() + "] entity has more than one pk!");
        }
        //}
    }

    public Iterator<ModelField> getPksIterator() {
        return getPkFields().iterator();
    }

    public List<ModelField> getPkFields() {
        //synchronized (fieldsLock) { // SCIPIO: 2018-09-29: Removed detrimental sync block for getters
        return this.fields.pks; // SCIPIO: 2018-09-29: fields member
        //}
    }

    public List<ModelField> getPkFieldsUnmodifiable() {
        return getPkFields();
    }

    public String getFirstPkFieldName() {
        List<String> pkFieldNames = this.getPkFieldNames();
        String idFieldName = null;
        if (UtilValidate.isNotEmpty(pkFieldNames)) {
            idFieldName = pkFieldNames.get(0);
        }
        return idFieldName;
    }

    public List<ModelField> getNoPkFields() { // SCIPIO
        return this.fields.noPks;
    }

    public int getNopksSize() {
        //synchronized (fieldsLock) { // SCIPIO: 2018-09-29: Removed detrimental sync block for getters
        return this.fields.noPks.size(); // SCIPIO: 2018-09-29: fields member
        //}
    }

    public Iterator<ModelField> getNopksIterator() {
        return getNopksCopy().iterator();
    }

    public List<ModelField> getNopksCopy() {
        //synchronized (fieldsLock) { // SCIPIO: 2018-09-29: Removed detrimental sync block for getters
        return new ArrayList<>(this.fields.noPks); // SCIPIO: 2018-09-29: fields member
        //}
    }

    public int getFieldsSize() {
        //synchronized (fieldsLock) { // SCIPIO: 2018-09-29: Removed detrimental sync block for getters
        return this.fields.fieldsList.size(); // SCIPIO: 2018-09-29: fields member
        //}
    }

    public Iterator<ModelField> getFieldsIterator() {
        //synchronized (fieldsLock) { // SCIPIO: 2018-09-29: Removed detrimental sync block for getters
        List<ModelField> newList = new ArrayList<>(this.fields.fieldsList); // SCIPIO: 2018-09-29: fields member
        return newList.iterator();
        //}
    }

    public List<ModelField> getFields() { // SCIPIO
        return this.fields.fieldsList;
    }

    public List<ModelField> getFieldsUnmodifiable() {
        //synchronized (fieldsLock) { // SCIPIO: 2018-09-29: Removed detrimental sync block for getters
        // SCIPIO: 2018-10-02: Extra ArrayList copy is useless
        //List<ModelField> newList = new ArrayList<>(this.fields.fieldsList); // SCIPIO: 2018-09-29: fields member
        //return Collections.unmodifiableList(newList);
        return this.fields.fieldsList; // SCIPIO: 2018-09-29: fields member
        //}
    }

    public List<ModelField> getSelectableFields() { // SCIPIO
        return this.fields.selectableFieldsList;
    }

    /** The col-name of the Field, the alias of the field if this is on a view-entity */
    public String getColNameOrAlias(String fieldName) {
        ModelField modelField = this.getField(fieldName);
        String fieldString = modelField.getColName();
        return fieldString;
    }

    public ModelField getField(String fieldName) {
        if (fieldName == null) return null;
        //synchronized (fieldsLock) { // SCIPIO: 2018-09-29: Removed detrimental sync block for getters
        return fields.fieldsMap.get(fieldName); // SCIPIO: 2018-09-29: fields member
        //}
    }

    public ModelField getFieldByColName(String colName) { // SCIPIO
        if (colName == null) return null;
        for(ModelField field : fields.fieldsList) {
            if (colName.equals(field.getColName())) {
                return field;
            }
        }
        return null;
    }

    /**
     * addField.
     * <p>
     * SCIPIO: <strong>WARNING:</strong> 2018-09-29: DO NOT CALL THIS METHOD FROM CLIENT CODE
     * OR CLIENT FRAMEWORK PATCHES!
     * This stock method should never have existed as a public method and if called prior
     * to entity engine loading will causing unpredictable results!
     * This method may be removed entirely in Scipio soon.
     */
    public void addField(ModelField field) {
        if (field == null)
            return;
        synchronized (fieldsLock) {
            // SCIPIO: Update member with copies for change atomicity
            this.fields = this.fields.add(field);
        }
    }

    /**
     * removeField.
     * <p>
     * SCIPIO: <strong>WARNING:</strong> 2018-09-29: DO NOT CALL THIS METHOD FROM CLIENT CODE
     * OR CLIENT FRAMEWORK PATCHES!
     * This stock method should never have existed as a public method and if called prior
     * to entity engine loading will causing unpredictable results!
     * This method may be removed entirely in Scipio soon.
     */
    public ModelField removeField(String fieldName) {
        if (fieldName == null) {
            return null;
        }
        synchronized (fieldsLock) {
            // SCIPIO: Create copies for change atomicity
            ModelField field = this.fields.fieldsMap.get(fieldName);
            this.fields = this.fields.remove(fieldName);
            return field;
        }
    }

    public List<String> getAllFieldNames() {
        //synchronized (fieldsLock) { // SCIPIO: 2018-09-29: Removed detrimental sync block for getters
        // SCIPIO: 2018-10-02: keySet() loses the field order defined in entitymodel.xml, so go through list
        //return new ArrayList<>(this.fields.fieldsMap.keySet()); // SCIPIO: 2018-09-29: fields member
        // TODO?: May want to make a pre-built this.fields.allFieldNames later if gets used more at runtime
        //List<String> allFieldNames = new ArrayList<>(this.fields.fieldsList.size());
        //for(ModelField field : this.fields.fieldsList) {
        //    allFieldNames.add(field.getName());
        //}
        //return allFieldNames;
        return this.fields.fieldNames;
        //}
    }

    public List<String> getPkFieldNames() {
        //synchronized (fieldsLock) { // SCIPIO: 2018-09-29: Removed detrimental sync block for getters
        return fields.pkFieldNames; // SCIPIO: 2018-09-29: fields member
        //}
    }

    public List<String> getNoPkFieldNames() {
        return fields.noPkFieldNames;
    }

    private List<String> getFieldNamesFromFieldVector(List<ModelField> modelFields) {
        List<String> nameList = new ArrayList<>(modelFields.size());
        for (ModelField field: modelFields) {
            nameList.add(field.getName());
        }
        return nameList;
    }

    /**
     * @return field names list, managed by entity-engine
     */
    public List<String> getAutomaticFieldNames() {
        List<String> nameList = new ArrayList<>(); // SCIPIO: switched to ArrayList
        if (! this.noAutoStamp) {
            nameList.add(STAMP_FIELD);
            nameList.add(STAMP_TX_FIELD);
            nameList.add(CREATE_STAMP_FIELD);
            nameList.add(CREATE_STAMP_TX_FIELD);
        }
        return nameList;
    }

    public List<ModelRelation> getRelations() { // SCIPIO: 2.1.0: Added
        return Collections.unmodifiableList(this.relations);
    }

    public int getRelationsSize() {
        return this.relations.size();
    }

    public int getRelationsOneSize() {
        int numRels = 0;
        Iterator<ModelRelation> relationsIter = this.getRelationsIterator();
        while (relationsIter.hasNext()) {
            ModelRelation modelRelation = relationsIter.next();
            if ("one".equals(modelRelation.getType())) {
                numRels++;
            }
        }
        return numRels;
    }

    public ModelRelation getRelation(int index) {
        return this.relations.get(index);
    }

    public Iterator<ModelRelation> getRelationsIterator() {
        return this.relations.iterator();
    }

    public List<ModelRelation> getRelationsList(boolean includeOne, boolean includeOneNoFk, boolean includeMany) {
        List<ModelRelation> relationsList = new ArrayList<>(this.getRelationsSize()); // SCIPIO: switched to ArrayList
        Iterator<ModelRelation> allIter = this.getRelationsIterator();
        while (allIter.hasNext()) {
            ModelRelation modelRelation = allIter.next();
            if (includeOne && "one".equals(modelRelation.getType())) {
                relationsList.add(modelRelation);
            } else if (includeOneNoFk && "one-nofk".equals(modelRelation.getType())) {
                relationsList.add(modelRelation);
            } else if (includeMany && "many".equals(modelRelation.getType())) {
                relationsList.add(modelRelation);
            }
        }
        return relationsList;
    }

    public List<ModelRelation> getRelationsOneList() {
        return getRelationsList(true, true, false);
    }

    public List<ModelRelation> getRelationsManyList() {
        return getRelationsList(false, false, true);
    }

    public ModelRelation getRelation(String relationName) {
        if (relationName == null) return null;
        for (ModelRelation relation: relations) {
            if (relationName.equals(relation.getTitle() + relation.getRelEntityName())) return relation;
        }
        return null;
    }

    public void addRelation(ModelRelation relation) {
        this.relations.add(relation);
    }

    public ModelRelation removeRelation(int index) {
        return this.relations.remove(index);
    }

    public int getIndexesSize() {
        return this.indexes.size();
    }

    public ModelIndex getIndex(int index) {
        return this.indexes.get(index);
    }

    public Iterator<ModelIndex> getIndexesIterator() {
        return this.indexes.iterator();
    }

    public ModelIndex getIndex(String indexName) {
        if (indexName == null) return null;
        for (ModelIndex index: indexes) {
            if (indexName.equals(index.getName())) return index;
        }
        return null;
    }

    public void addIndex(ModelIndex index) {
        this.indexes.add(index);
    }

    public ModelIndex removeIndex(int index) {
        return this.indexes.remove(index);
    }

    public int getViewEntitiesSize() {
        //synchronized (viewEntities) { // SCIPIO: 2018-09-29: Removed detrimental sync block for getters
        return this.viewEntities.size();
        //}
    }

    public Iterator<String> getViewConvertorsIterator() {
        //synchronized (viewEntities) { // SCIPIO: 2018-09-29: Removed detrimental sync block for getters
        // SCIPIO: 2018-09-29: Don't need to copy anymore (using unmodifiableSet)
        //return new HashSet<>(this.viewEntities).iterator();
        return this.viewEntities.iterator();
        //}
    }

    public void addViewEntity(ModelViewEntity view) {
        // SCIPIO: 2018-09-29: Switched to fields lock for this
        //synchronized (viewEntities) {
        synchronized (fieldsLock) {
            // SCIPIO: 2018-09-29: Now read-only; make copy
            //this.viewEntities.add(view.getEntityName());
            Set<String> viewEntities = new HashSet<>(this.viewEntities);
            viewEntities.add(view.getEntityName());
            this.viewEntities = Collections.unmodifiableSet(viewEntities);
        }
    }

    public List<? extends Map<String, Object>> convertToViewValues(String viewEntityName, GenericEntity entity) {
        if (entity == null || entity == GenericEntity.NULL_ENTITY || entity == GenericValue.NULL_VALUE) return UtilMisc.toList(entity);
        ModelViewEntity view = (ModelViewEntity) entity.getDelegator().getModelEntity(viewEntityName);
        return view.convert(getEntityName(), entity);
    }

    public boolean removeViewEntity(String viewEntityName) {
        // SCIPIO: 2018-09-29: Switched to fields lock for this
        //synchronized (viewEntities) {
        synchronized (fieldsLock) {
            // SCIPIO: 2018-09-29: Now read-only; make copy
            //return this.viewEntities.remove(viewEntityName);
            Set<String> viewEntities = new HashSet<>(this.viewEntities);
            boolean removeResult = viewEntities.remove(viewEntityName);
            this.viewEntities = Collections.unmodifiableSet(viewEntities);
            return removeResult;
        }
    }

    public boolean removeViewEntity(ModelViewEntity viewEntity) {
       return removeViewEntity(viewEntity.getEntityName());
    }

    public String nameString(List<ModelField> flds) {
        return nameString(flds, ", ", "");
    }

    public String nameString(List<ModelField> flds, String separator, String afterLast) {
        StringBuilder returnString = new StringBuilder();

        if (flds.size() < 1) {
            return "";
        }

        int i = 0;

        for (; i < flds.size() - 1; i++) {
            returnString.append(flds.get(i).getName());
            returnString.append(separator);
        }
        returnString.append(flds.get(i).getName());
        returnString.append(afterLast);
        return returnString.toString();
    }

    public String typeNameString(ModelField... flds) {
        return typeNameString(Arrays.asList(flds));
    }

    public String typeNameString(List<ModelField> flds) {
        StringBuilder returnString = new StringBuilder();

        if (flds.size() < 1) {
            return "";
        }

        int i = 0;

        for (; i < flds.size() - 1; i++) {
            ModelField curField = flds.get(i);
            returnString.append(curField.getType());
            returnString.append(" ");
            returnString.append(curField.getName());
            returnString.append(", ");
        }
        ModelField curField = flds.get(i);
        returnString.append(curField.getType());
        returnString.append(" ");
        returnString.append(curField.getName());
        return returnString.toString();
    }

    public String fieldNameString() {
        return fieldNameString(", ", "");
    }

    public String fieldNameString(String separator, String afterLast) {
        return nameString(getFieldsUnmodifiable(), separator, afterLast);
    }

    public String fieldTypeNameString() {
        return typeNameString(getFieldsUnmodifiable());
    }

    public String primKeyClassNameString() {
        return typeNameString(getPkFields());
    }

    public String pkNameString() {
        return pkNameString(", ", "");
    }

    public String pkNameString(String separator, String afterLast) {
        return nameString(getPkFields(), separator, afterLast);
    }

    public String nonPkNullList() {
        return fieldsStringList(getFieldsUnmodifiable(), "null", ", ", false, true);
    }

    @Deprecated
    public String fieldsStringList(String eachString, String separator, ModelField... flds) {
        return fieldsStringList(Arrays.asList(flds), eachString, separator, false, false);
    }

    public StringBuilder fieldsStringList(StringBuilder sb, String eachString, String separator, ModelField... flds) {
        return fieldsStringList(Arrays.asList(flds), sb, eachString, separator, false, false);
    }

    @Deprecated
    public String fieldsStringList(List<ModelField> flds, String eachString, String separator) {
        return fieldsStringList(flds, eachString, separator, false, false);
    }

    public StringBuilder fieldsStringList(List<ModelField> flds, StringBuilder sb, String eachString, String separator) {
        return fieldsStringList(flds, sb, eachString, separator, false, false);
    }

    @Deprecated
    public String fieldsStringList(String eachString, String separator, boolean appendIndex, ModelField... flds) {
        return fieldsStringList(Arrays.asList(flds), eachString, separator, appendIndex, false);
    }

    public StringBuilder fieldsStringList(StringBuilder sb, String eachString, String separator, boolean appendIndex, ModelField... flds) {
        return fieldsStringList(Arrays.asList(flds), sb, eachString, separator, appendIndex, false);
    }

    @Deprecated
    public String fieldsStringList(List<ModelField> flds, String eachString, String separator, boolean appendIndex) {
        return fieldsStringList(flds, eachString, separator, appendIndex, false);
    }

    public StringBuilder fieldsStringList(List<ModelField> flds, StringBuilder sb, String eachString, String separator, boolean appendIndex) {
        return fieldsStringList(flds, sb, eachString, separator, appendIndex, false);
    }

    @Deprecated
    public String fieldsStringList(String eachString, String separator, boolean appendIndex, boolean onlyNonPK, ModelField... flds) {
        return fieldsStringList(Arrays.asList(flds), eachString, separator, appendIndex, onlyNonPK);
    }

    public StringBuilder fieldsStringList(StringBuilder sb, String eachString, String separator, boolean appendIndex, boolean onlyNonPK, ModelField... flds) {
        return fieldsStringList(Arrays.asList(flds), sb, eachString, separator, appendIndex, onlyNonPK);
    }

    @Deprecated
    public String fieldsStringList(List<ModelField> flds, String eachString, String separator, boolean appendIndex, boolean onlyNonPK) {
        return fieldsStringList(flds, new StringBuilder(), eachString, separator, appendIndex, onlyNonPK).toString();
    }

    public StringBuilder fieldsStringList(List<ModelField> flds, StringBuilder sb, String eachString, String separator, boolean appendIndex, boolean onlyNonPK) {
        if (flds.size() < 1) {
            return sb;
        }

        int i = 0;

        for (; i < flds.size(); i++) {
            if (onlyNonPK && flds.get(i).getIsPk()) continue;
            sb.append(eachString);
            if (appendIndex) sb.append(i + 1);
            if (i < flds.size() - 1) sb.append(separator);
        }
        return sb;
    }

    @Deprecated
    public String colNameString(ModelField... flds) {
        return colNameString(new StringBuilder(), "", flds).toString();
    }

    public StringBuilder colNameString(StringBuilder sb, String prefix,  ModelField... flds) {
        return colNameString(Arrays.asList(flds), sb, prefix);
    }

    @Deprecated
    public String colNameString(List<ModelField> flds) {
        return colNameString(flds, new StringBuilder(), "", ", ", "", false).toString();
    }

    public StringBuilder colNameString(List<ModelField> flds, StringBuilder sb, String prefix) {
        return colNameString(flds, sb, prefix, ", ", "", false);
    }

    @Deprecated
    public String colNameString(String separator, String afterLast, boolean alias, ModelField... flds) {
        return colNameString(Arrays.asList(flds), new StringBuilder(), "", separator, afterLast, alias).toString();
    }

    public StringBuilder colNameString(StringBuilder sb, String prefix, String separator, String afterLast, boolean alias, ModelField... flds) {
        return colNameString(Arrays.asList(flds), sb, prefix, separator, afterLast, alias);
    }

    @Deprecated
    public String colNameString(List<ModelField> flds, String separator, String afterLast, boolean alias) {
        return colNameString(flds, new StringBuilder(), "", separator, afterLast, alias).toString();
    }

    public StringBuilder colNameString(List<ModelField> flds, StringBuilder sb, String prefix, String separator, String afterLast, boolean alias) {
        if (flds.size() < 1) {
            return sb;
        }

        sb.append(prefix);
        Iterator<ModelField> fldsIt = flds.iterator();
        while (fldsIt.hasNext()) {
            ModelField field = fldsIt.next();
            sb.append(field.getColName());
            if (fldsIt.hasNext()) {
                sb.append(separator);
            }
        }

        sb.append(afterLast);
        return sb;
    }

    public String classNameString(ModelField... flds) {
        return classNameString(Arrays.asList(flds));
    }

    public String classNameString(List<ModelField> flds) {
        return classNameString(flds, ", ", "");
    }

    public String classNameString(String separator, String afterLast, ModelField... flds) {
        return classNameString(Arrays.asList(flds), separator, afterLast);
    }

    public String classNameString(List<ModelField> flds, String separator, String afterLast) {
        StringBuilder returnString = new StringBuilder();

        if (flds.size() < 1) {
            return "";
        }

        int i = 0;

        for (; i < flds.size() - 1; i++) {
            returnString.append(ModelUtil.upperFirstChar(flds.get(i).getName()));
            returnString.append(separator);
        }
        returnString.append(ModelUtil.upperFirstChar(flds.get(i).getName()));
        returnString.append(afterLast);
        return returnString.toString();
    }

    public String finderQueryString(ModelField... flds) {
        return finderQueryString(Arrays.asList(flds));
    }

    public String finderQueryString(List<ModelField> flds) {
        StringBuilder returnString = new StringBuilder();

        if (flds.size() < 1) {
            return "";
        }
        int i = 0;

        for (; i < flds.size() - 1; i++) {
            returnString.append(flds.get(i).getColName());
            returnString.append(" like {");
            returnString.append(i);
            returnString.append("} AND ");
        }
        returnString.append(flds.get(i).getColName());
        returnString.append(" like {");
        returnString.append(i);
        returnString.append("}");
        return returnString.toString();
    }

    public String httpArgList(ModelField... flds) {
        return httpArgList(Arrays.asList(flds));
    }

    public String httpArgList(List<ModelField> flds) {
        StringBuilder returnString = new StringBuilder();

        if (flds.size() < 1) {
            return "";
        }
        int i = 0;

        for (; i < flds.size() - 1; i++) {
            returnString.append("\"");
            returnString.append(tableName);
            returnString.append("_");
            returnString.append(flds.get(i).getColName());
            returnString.append("=\" + ");
            returnString.append(flds.get(i).getName());
            returnString.append(" + \"&\" + ");
        }
        returnString.append("\"");
        returnString.append(tableName);
        returnString.append("_");
        returnString.append(flds.get(i).getColName());
        returnString.append("=\" + ");
        returnString.append(flds.get(i).getName());
        return returnString.toString();
    }

    public String httpArgListFromClass(ModelField... flds) {
        return httpArgListFromClass(Arrays.asList(flds));
    }

    public String httpArgListFromClass(List<ModelField> flds) {
        StringBuilder returnString = new StringBuilder();

        if (flds.size() < 1) {
            return "";
        }

        int i = 0;

        for (; i < flds.size() - 1; i++) {
            returnString.append("\"");
            returnString.append(tableName);
            returnString.append("_");
            returnString.append(flds.get(i).getColName());
            returnString.append("=\" + ");
            returnString.append(ModelUtil.lowerFirstChar(entityName));
            returnString.append(".get");
            returnString.append(ModelUtil.upperFirstChar(flds.get(i).getName()));
            returnString.append("() + \"&\" + ");
        }
        returnString.append("\"");
        returnString.append(tableName);
        returnString.append("_");
        returnString.append(flds.get(i).getColName());
        returnString.append("=\" + ");
        returnString.append(ModelUtil.lowerFirstChar(entityName));
        returnString.append(".get");
        returnString.append(ModelUtil.upperFirstChar(flds.get(i).getName()));
        returnString.append("()");
        return returnString.toString();
    }

    public String httpArgListFromClass(String entityNameSuffix, ModelField... flds) {
        return httpArgListFromClass(Arrays.asList(flds), entityNameSuffix);
    }

    public String httpArgListFromClass(List<ModelField> flds, String entityNameSuffix) {
        StringBuilder returnString = new StringBuilder();

        if (flds.size() < 1) {
            return "";
        }

        int i = 0;

        for (; i < flds.size() - 1; i++) {
            returnString.append("\"");
            returnString.append(tableName);
            returnString.append("_");
            returnString.append(flds.get(i).getColName());
            returnString.append("=\" + ");
            returnString.append(ModelUtil.lowerFirstChar(entityName));
            returnString.append(entityNameSuffix);
            returnString.append(".get");
            returnString.append(ModelUtil.upperFirstChar(flds.get(i).getName()));
            returnString.append("() + \"&\" + ");
        }
        returnString.append("\"");
        returnString.append(tableName);
        returnString.append("_");
        returnString.append(flds.get(i).getColName());
        returnString.append("=\" + ");
        returnString.append(ModelUtil.lowerFirstChar(entityName));
        returnString.append(entityNameSuffix);
        returnString.append(".get");
        returnString.append(ModelUtil.upperFirstChar(flds.get(i).getName()));
        returnString.append("()");
        return returnString.toString();
    }

    public String httpRelationArgList(ModelRelation relation, ModelField... flds) {
        return httpRelationArgList(Arrays.asList(flds), relation);
    }

    public String httpRelationArgList(List<ModelField> flds, ModelRelation relation) {
        StringBuilder returnString = new StringBuilder();

        if (flds.size() < 1) {
            return "";
        }

        int i = 0;

        for (; i < flds.size() - 1; i++) {
            ModelKeyMap keyMap = relation.findKeyMapByRelated(flds.get(i).getName());

            if (keyMap != null) {
                returnString.append("\"");
                returnString.append(tableName);
                returnString.append("_");
                returnString.append(flds.get(i).getColName());
                returnString.append("=\" + ");
                returnString.append(ModelUtil.lowerFirstChar(relation.getModelEntity().entityName));
                returnString.append(".get");
                returnString.append(ModelUtil.upperFirstChar(keyMap.getFieldName()));
                returnString.append("() + \"&\" + ");
            } else {
                Debug.logWarning("-- -- ENTITYGEN ERROR:httpRelationArgList: Related Key in Key Map not found for name: " + flds.get(i).getName() + " related entity: " + relation.getRelEntityName() + " main entity: " + relation.getModelEntity().entityName + " type: " + relation.getType(), module);
            }
        }
        ModelKeyMap keyMap = relation.findKeyMapByRelated(flds.get(i).getName());

        if (keyMap != null) {
            returnString.append("\"");
            returnString.append(tableName);
            returnString.append("_");
            returnString.append(flds.get(i).getColName());
            returnString.append("=\" + ");
            returnString.append(ModelUtil.lowerFirstChar(relation.getModelEntity().entityName));
            returnString.append(".get");
            returnString.append(ModelUtil.upperFirstChar(keyMap.getFieldName()));
            returnString.append("()");
        } else {
            Debug.logWarning("-- -- ENTITYGEN ERROR:httpRelationArgList: Related Key in Key Map not found for name: " + flds.get(i).getName() + " related entity: " + relation.getRelEntityName() + " main entity: " + relation.getModelEntity().entityName + " type: " + relation.getType(), module);
        }
        return returnString.toString();
    }

    /*
     public String httpRelationArgList(ModelRelation relation) {
     String returnString = "";
     if (relation.keyMaps.size() < 1) { return ""; }

     int i = 0;
     for (; i < relation.keyMaps.size() - 1; i++) {
     ModelKeyMap keyMap = (ModelKeyMap)relation.keyMaps.get(i);
     if (keyMap != null)
     returnString = returnString + "\"" + tableName + "_" + keyMap.relColName + "=\" + " + ModelUtil.lowerFirstChar(relation.mainEntity.entityName) + ".get" + ModelUtil.upperFirstChar(keyMap.fieldName) + "() + \"&\" + ";
     }
     ModelKeyMap keyMap = (ModelKeyMap)relation.keyMaps.get(i);
     returnString = returnString + "\"" + tableName + "_" + keyMap.relColName + "=\" + " + ModelUtil.lowerFirstChar(relation.mainEntity.entityName) + ".get" + ModelUtil.upperFirstChar(keyMap.fieldName) + "()";
     return returnString;
     }
     */
    public String typeNameStringRelatedNoMapped(ModelRelation relation, ModelField... flds) {
        return typeNameStringRelatedNoMapped(Arrays.asList(flds), relation);
    }

    public String typeNameStringRelatedNoMapped(List<ModelField> flds, ModelRelation relation) {
        StringBuilder returnString = new StringBuilder();

        if (flds.size() < 1) {
            return "";
        }

        int i = 0;

        if (relation.findKeyMapByRelated(flds.get(i).getName()) == null) {
            returnString.append(flds.get(i).getType());
            returnString.append(" ");
            returnString.append(flds.get(i).getName());
        }
        i++;
        for (; i < flds.size(); i++) {
            if (relation.findKeyMapByRelated(flds.get(i).getName()) == null) {
                if (returnString.length() > 0) returnString.append(", ");
                returnString.append(flds.get(i).getType());
                returnString.append(" ");
                returnString.append(flds.get(i).getName());
            }
        }
        return returnString.toString();
    }

    public String typeNameStringRelatedAndMain(ModelRelation relation, ModelField... flds) {
        return typeNameStringRelatedAndMain(Arrays.asList(flds), relation);
    }

    public String typeNameStringRelatedAndMain(List<ModelField> flds, ModelRelation relation) {
        StringBuilder returnString = new StringBuilder();

        if (flds.size() < 1) {
            return "";
        }

        int i = 0;

        for (; i < flds.size() - 1; i++) {
            ModelKeyMap keyMap = relation.findKeyMapByRelated(flds.get(i).getName());

            if (keyMap != null) {
                returnString.append(keyMap.getFieldName());
                returnString.append(", ");
            } else {
                returnString.append(flds.get(i).getName());
                returnString.append(", ");
            }
        }
        ModelKeyMap keyMap = relation.findKeyMapByRelated(flds.get(i).getName());

        if (keyMap != null) returnString.append(keyMap.getFieldName());
        else returnString.append(flds.get(i).getName());
        return returnString.toString();
    }

    public int compareTo(ModelEntity otherModelEntity) {

        /* This DOESN'T WORK, so forget it... using two passes
         //sort list by fk dependencies

         if (this.getEntityName().equals(otherModelEntity.getEntityName())) {
         return 0;
         }

         //look through relations for dependencies from this entity to the other
         Iterator relationsIter = this.getRelationsIterator();
         while (relationsIter.hasNext()) {
         ModelRelation modelRelation = (ModelRelation) relationsIter.next();

         if ("one".equals(modelRelation.getType()) && modelRelation.getRelEntityName().equals(otherModelEntity.getEntityName())) {
         //this entity is dependent on the other entity, so put that entity earlier in the list
         return -1;
         }
         }

         //look through relations for dependencies from the other to this entity
         Iterator otherRelationsIter = otherModelEntity.getRelationsIterator();
         while (otherRelationsIter.hasNext()) {
         ModelRelation modelRelation = (ModelRelation) otherRelationsIter.next();

         if ("one".equals(modelRelation.getType()) && modelRelation.getRelEntityName().equals(this.getEntityName())) {
         //the other entity is dependent on this entity, so put that entity later in the list
         return 1;
         }
         }

         return 0;
         */

        return this.getEntityName().compareTo(otherModelEntity.getEntityName());
    }

    public void convertFieldMapInPlace(Map<String, Object> inContext, Delegator delegator) {
        convertFieldMapInPlace(inContext, delegator.getModelFieldTypeReader(this));
    }
    public void convertFieldMapInPlace(Map<String, Object> inContext, ModelFieldTypeReader modelFieldTypeReader) {
        Iterator<ModelField> modelFields = this.getFieldsIterator();
        while (modelFields.hasNext()) {
            ModelField modelField = modelFields.next();
            String fieldName = modelField.getName();
            Object oldValue = inContext.get(fieldName);
            if (oldValue != null) {
                inContext.put(fieldName, this.convertFieldValue(modelField, oldValue, modelFieldTypeReader, inContext));
            }
        }
    }

    public Object convertFieldValue(String fieldName, Object value, Delegator delegator) {
        ModelField modelField = this.getField(fieldName);
        if (modelField == null) {
            String errMsg = "Could not convert field value: could not find an entity field for the name: [" + fieldName + "] on the [" + this.getEntityName() + "] entity.";
            throw new IllegalArgumentException(errMsg);
        }
        return convertFieldValue(modelField, value, delegator);
    }

    public Object convertFieldValue(ModelField modelField, Object value, Delegator delegator) {
        if (value == null || value == GenericEntity.NULL_FIELD) {
            return null;
        }
        String fieldJavaType = null;
        try {
            fieldJavaType = delegator.getEntityFieldType(this, modelField.getType()).getJavaType();
        } catch (GenericEntityException e) {
            String errMsg = "Could not convert field value: could not find Java type for the field: [" + modelField.getName() + "] on the [" + this.getEntityName() + "] entity: " + e.toString();
            Debug.logError(e, errMsg, module);
            throw new IllegalArgumentException(errMsg);
        }
        try {
            return ObjectType.simpleTypeConvert(value, fieldJavaType, null, null, false);
        } catch (GeneralException e) {
            String errMsg = "Could not convert field value for the field: [" + modelField.getName() + "] on the [" + this.getEntityName() + "] entity to the [" + fieldJavaType + "] type for the value [" + value + "]: " + e.toString();
            Debug.logError(e, errMsg, module);
            throw new IllegalArgumentException(errMsg);
        }
    }

    /** Convert a field value from one Java data type to another. This is the preferred method -
     * which takes into consideration the user's locale and time zone (for conversions that
     * require them).
     * @return the converted value
     */
    public Object convertFieldValue(ModelField modelField, Object value, Delegator delegator, Map<String, ? extends Object> context) {
        ModelFieldTypeReader modelFieldTypeReader = delegator.getModelFieldTypeReader(this);
        return this.convertFieldValue(modelField, value, modelFieldTypeReader, context);
    }
    /** Convert a field value from one Java data type to another. This is the preferred method -
     * which takes into consideration the user's locale and time zone (for conversions that
     * require them).
     * @return the converted value
     */
    public Object convertFieldValue(ModelField modelField, Object value, ModelFieldTypeReader modelFieldTypeReader, Map<String, ? extends Object> context) {
        if (value == null || value == GenericEntity.NULL_FIELD) {
            return null;
        }
        String fieldJavaType = modelFieldTypeReader.getModelFieldType(modelField.getType()).getJavaType();
        try {
            return ObjectType.simpleTypeConvert(value, fieldJavaType, null, (TimeZone) context.get("timeZone"), (Locale) context.get("locale"), true);
        } catch (GeneralException e) {
            String errMsg = "Could not convert field value for the field: [" + modelField.getName() + "] on the [" + this.getEntityName() + "] entity to the [" + fieldJavaType + "] type for the value [" + value + "]: " + e.toString();
            Debug.logError(e, errMsg, module);
            throw new IllegalArgumentException(errMsg);
        }
    }

    /**
     * @return Returns the noAutoStamp.
     */
    public boolean getNoAutoStamp() {
        return this.noAutoStamp;
    }

    /**
     * @param noAutoStamp The noAutoStamp to set.
     */
    public void setNoAutoStamp(boolean noAutoStamp) {
        this.noAutoStamp = noAutoStamp;
    }

    @Override
    public String toString() {
        return "ModelEntity[" + getEntityName() + "]";
    }

    public Element toXmlElement(Document document, String packageName) {
        if (UtilValidate.isNotEmpty(this.getPackageName()) && !packageName.equals(this.getPackageName())) {
            Debug.logWarning("Export EntityModel XML Element [" + this.getEntityName() + "] with a NEW package - " + packageName, module);
        }

        Element root = document.createElement("entity");
        root.setAttribute("entity-name", this.getEntityName());
        if (!this.getEntityName().equals(ModelUtil.dbNameToClassName(this.getPlainTableName())) ||
                !ModelUtil.javaNameToDbName(this.getEntityName()).equals(this.getPlainTableName())) {
                root.setAttribute("table-name", this.getPlainTableName());
        }
        root.setAttribute("package-name", packageName);

        // additional elements
        if (UtilValidate.isNotEmpty(this.getDefaultResourceName())) {
            root.setAttribute("default-resource-name", this.getDefaultResourceName());
        }

        if (UtilValidate.isNotEmpty(this.getDependentOn())) {
            root.setAttribute("dependent-on", this.getDependentOn());
        }

        if (this.getDoLock()) {
            root.setAttribute("enable-lock", "true");
        }

        if (this.getNoAutoStamp()) {
            root.setAttribute("no-auto-stamp", "true");
        }

        if (this.getNeverCache()) {
            root.setAttribute("never-cache", "true");
        }

        if (this.getNeverCheck()) {
            root.setAttribute("never-check", "true");
        }

        if (!this.getAutoClearCache()) {
            root.setAttribute("auto-clear-cache", "false");
        }

        if (this.getSequenceBankSize() != null) {
            root.setAttribute("sequence-bank-size", this.getSequenceBankSize().toString());
        }

        if (UtilValidate.isNotEmpty(this.getTitle())) {
            root.setAttribute("title", this.getTitle());
        }

        if (UtilValidate.isNotEmpty(this.getCopyright())) {
            root.setAttribute("copyright", this.getCopyright());
        }

        if (UtilValidate.isNotEmpty(this.getAuthor())) {
            root.setAttribute("author", this.getAuthor());
        }

        if (UtilValidate.isNotEmpty(this.getVersion())) {
            root.setAttribute("version", this.getVersion());
        }

        // description element
        if (UtilValidate.isNotEmpty(this.getDescription())) {
            UtilXml.addChildElementValue(root, "description", this.getDescription(), document);
        }

        // append field elements
        Iterator<ModelField> fieldIter = this.getFieldsIterator();
        while (fieldIter.hasNext()) {
            ModelField field = fieldIter.next();
            if (!field.getIsAutoCreatedInternal()) {
                root.appendChild(field.toXmlElement(document));
            }
        }

        // append PK elements
        Iterator<ModelField> pkIter = this.getPksIterator();
        while (pkIter != null && pkIter.hasNext()) {
            ModelField pk = pkIter.next();
            Element pkey = document.createElement("prim-key");
            pkey.setAttribute("field", pk.getName());
            root.appendChild(pkey);
        }

        // append relation elements
        Iterator<ModelRelation> relIter = this.getRelationsIterator();
        while (relIter.hasNext()) {
            ModelRelation rel = relIter.next();
            root.appendChild(rel.toXmlElement(document));
        }

        // append index elements
        Iterator<ModelIndex> idxIter = this.getIndexesIterator();
        while (idxIter.hasNext()) {
            ModelIndex idx = idxIter.next();
            root.appendChild(idx.toXmlElement(document));

        }

        return root;
    }

    public Element toXmlElement(Document document) {
        return this.toXmlElement(document, this.getPackageName());
    }

    /**
     * Writes entity model information in the Apple EOModelBundle format.
     *
     * For document structure and definition see: http://developer.apple.com/documentation/InternetWeb/Reference/WO_BundleReference/Articles/EOModelBundle.html
     *
     * For examples see the JavaRealEstate.framework and JavaBusinessLogic.framework packages which are in the /Library/Frameworks directory after installing the WebObjects Examples package (get latest version of WebObjects download for this).
     *
     * This is based on examples and documentation from WebObjects 5.4, downloaded 20080221.
     *
     * @param writer
     * @param entityPrefix
     * @param helperName
     */
    public void writeEoModelText(PrintWriter writer, String entityPrefix, String helperName, Set<String> entityNameIncludeSet, ModelReader entityModelReader) throws GenericEntityException {
        if (entityPrefix == null) entityPrefix = "";
        if (helperName == null) helperName = "localderby";

        UtilPlist.writePlistPropertyMap(this.createEoModelMap(entityPrefix, helperName, entityNameIncludeSet, entityModelReader), 0, writer, false);
    }


    public Map<String, Object> createEoModelMap(String entityPrefix, String helperName, Set<String> entityNameIncludeSet, ModelReader entityModelReader) throws GenericEntityException {
        final boolean useRelationshipNames = false;
        ModelFieldTypeReader modelFieldTypeReader = ModelFieldTypeReader.getModelFieldTypeReader(helperName);

        Map<String, Object> topLevelMap = new LinkedHashMap<>();

        topLevelMap.put("name", this.getEntityName());
        topLevelMap.put("externalName", this.getTableName(helperName));
        topLevelMap.put("className", "EOGenericRecord");

        // SCIPIO: 2018-09-29: Use local var to avoid potential sync issues from consecutive instance reads
        List<ModelField> fieldsList = this.fields.fieldsList;

        // for classProperties add field names AND relationship names to get a nice, complete chart
        List<String> classPropertiesList = new ArrayList<>(fieldsList.size() + this.relations.size()); // SCIPIO: switched to ArrayList
        topLevelMap.put("classProperties", classPropertiesList);
        for (ModelField field: fieldsList) {
            if (field.getIsAutoCreatedInternal()) continue;
            if (field.getIsPk()) {
                classPropertiesList.add(field.getName() + "*");
            } else {
                classPropertiesList.add(field.getName());
            }
        }
        for (ModelRelation relationship: this.relations) {
            if (!entityNameIncludeSet.contains(relationship.getRelEntityName())) continue;
            if (useRelationshipNames || relationship.isAutoRelation()) {
                classPropertiesList.add(relationship.getCombinedName());
            }
        }

        // attributes
        List<Map<String, Object>> attributesList = new ArrayList<>(fieldsList.size()); // SCIPIO: switched to ArrayList
        topLevelMap.put("attributes", attributesList);
        for (ModelField field: fieldsList) {
            if (field.getIsAutoCreatedInternal()) continue;

            ModelFieldType fieldType = modelFieldTypeReader.getModelFieldType(field.getType());

            Map<String, Object> attributeMap = new LinkedHashMap<>();
            attributesList.add(attributeMap);

            if (field.getIsPk()) {
                attributeMap.put("name", field.getName() + "*");
            } else {
                attributeMap.put("name", field.getName());
            }
            attributeMap.put("columnName", field.getColName());
            attributeMap.put("valueClassName", fieldType.getJavaType());

            String sqlType = fieldType.getSqlType();
            if (sqlType.indexOf('(') >= 0) {
                attributeMap.put("externalType", sqlType.substring(0, sqlType.indexOf('(')));
                // since there is a field length set that
                String widthStr = sqlType.substring(sqlType.indexOf('(') + 1, sqlType.indexOf(')'));
                // if there is a comma split by it for width,precision
                if (widthStr.indexOf(',') >= 0) {
                    attributeMap.put("width", widthStr.substring(0, widthStr.indexOf(',')));
                    // since there is a field precision set that
                    attributeMap.put("precision", widthStr.substring(widthStr.indexOf(',') + 1));
                } else {
                    attributeMap.put("width", widthStr);
                }
            } else {
                attributeMap.put("externalType", sqlType);
            }
        }

        // primaryKeyAttributes
        List<String> primaryKeyAttributesList = new ArrayList<>(getPksSize()); // SCIPIO: switched to ArrayList
        topLevelMap.put("primaryKeyAttributes", primaryKeyAttributesList);
        for (ModelField pkField : getPkFields()) {
            primaryKeyAttributesList.add(pkField.getName());
        }

        // relationships
        List<Map<String, Object>> relationshipsMapList = new ArrayList<>(this.relations.size()); // SCIPIO: switched to ArrayList
        for (ModelRelation relationship: this.relations) {
            if (entityNameIncludeSet.contains(relationship.getRelEntityName())) {
                ModelEntity relEntity = entityModelReader.getModelEntity(relationship.getRelEntityName());

                Map<String, Object> relationshipMap = new LinkedHashMap<>();
                relationshipsMapList.add(relationshipMap);

                if (useRelationshipNames || relationship.isAutoRelation()) {
                    relationshipMap.put("name", relationship.getCombinedName());
                } else {
                    relationshipMap.put("name", relationship.getKeyMaps().iterator().next().getFieldName());
                }
                relationshipMap.put("destination", relationship.getRelEntityName());
                if ("many".equals(relationship.getType())) {
                    relationshipMap.put("isToMany", "Y");
                    relationshipMap.put("isMandatory", "N");
                } else {
                    relationshipMap.put("isToMany", "N");
                    relationshipMap.put("isMandatory", "Y");
                }
                relationshipMap.put("joinSemantic", "EOInnerJoin");


                List<Map<String, Object>> joinsMapList = new ArrayList<>(relationship.getKeyMaps().size()); // SCIPIO: switched to ArrayList
                relationshipMap.put("joins", joinsMapList);
                for (ModelKeyMap keyMap: relationship.getKeyMaps()) {
                    Map<String, Object> joinsMap = new LinkedHashMap<>();
                    joinsMapList.add(joinsMap);

                    ModelField thisField = this.getField(keyMap.getFieldName());
                    if (thisField != null && thisField.getIsPk()) {
                        joinsMap.put("sourceAttribute", keyMap.getFieldName() + "*");
                    } else {
                        joinsMap.put("sourceAttribute", keyMap.getFieldName());
                    }

                    ModelField relField = null;
                    if (relEntity != null) relField = relEntity.getField(keyMap.getRelFieldName());
                    if (relField != null && relField.getIsPk()) {
                        joinsMap.put("destinationAttribute", keyMap.getRelFieldName() + "*");
                    } else {
                        joinsMap.put("destinationAttribute", keyMap.getRelFieldName());
                    }
                }
            }
        }
        if (relationshipsMapList.size() > 0) {
            topLevelMap.put("relationships", relationshipsMapList);
        }

        return topLevelMap;
    }

    public String getAuthor() {
        return modelInfo.getAuthor();
    }

    public String getCopyright() {
        return modelInfo.getCopyright();
    }

    public String getDefaultResourceName() {
        return modelInfo.getDefaultResourceName();
    }

    public String getDescription() {
        return modelInfo.getDescription();
    }

    public String getTitle() {
        return modelInfo.getTitle();
    }

    public String getVersion() {
        return modelInfo.getVersion();
    }

    public boolean getPkMapFromId(Map<String, Object> out, String id, Delegator delegator) { // SCIPIO
        boolean valid;
        if (getPksSize() == 1) {
            ModelField pkField = fields.pks.get(0);
            Object pkFieldValue = UtilValidate.nullIfEmptyString(convertFieldValue(pkField, id, delegator));
            out.put(pkField.getName(), pkFieldValue);
            valid = (pkFieldValue != null);
        } else {
            StringTokenizer st = new StringTokenizer(id, getIdSep());
            int i = 0;
            valid = true;
            while (st.hasMoreTokens() && i < getPksSize()) {
                String pkPart = st.nextToken();
                ModelField pkField = fields.pks.get(i);
                Object pkFieldValue = UtilValidate.nullIfEmptyString(convertFieldValue(pkField, pkPart, delegator));
                out.put(pkField.getName(), pkFieldValue);
                if (pkFieldValue == null) {
                    valid = false;
                }
                i++;
            }
            if (i < getPksSize() || st.hasMoreTokens()) {
                valid = false;
            }
        }
        return valid;
    }

    public Map<String, Object> getPkMapFromId(String id, Delegator delegator) { // SCIPIO
        Map<String, Object> pkMap = new LinkedHashMap<>();
        return getPkMapFromId(pkMap, id, delegator) ? pkMap : null;
    }

    public String getIdSep() { // SCIPIO
        return "::";
    }

    public Boolean getAliasColumns() {
        return aliasColumns;
    }
}
