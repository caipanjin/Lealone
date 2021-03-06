/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.codefollower.lealone.atomicdb.db.composites;

import java.io.DataInput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Comparator;

import com.codefollower.lealone.atomicdb.cql.CQL3Row;
import com.codefollower.lealone.atomicdb.cql.ColumnIdentifier;
import com.codefollower.lealone.atomicdb.db.Cell;
import com.codefollower.lealone.atomicdb.db.ColumnSerializer;
import com.codefollower.lealone.atomicdb.db.OnDiskAtom;
import com.codefollower.lealone.atomicdb.db.filter.IDiskAtomFilter;
import com.codefollower.lealone.atomicdb.db.filter.NamesQueryFilter;
import com.codefollower.lealone.atomicdb.db.marshal.AbstractType;
import com.codefollower.lealone.atomicdb.db.marshal.CollectionType;
import com.codefollower.lealone.atomicdb.db.marshal.ColumnToCollectionType;
import com.codefollower.lealone.atomicdb.io.ISerializer;
import com.codefollower.lealone.atomicdb.io.IVersionedSerializer;

/**
 * The type of CellNames.
 *
 * In the same way that a CellName is a Composite, a CellNameType is a CType, but
 * with a number of method specific to cell names.
 *
 * On top of the dichotomy simple/truly-composite of composites, cell names comes
 * in 2 variants: "dense" and "sparse". The sparse ones are CellName where one of
 * the component (the last or second-to-last for collections) is used to store the
 * CQL3 column name. Dense are those for which it's not the case.
 *
 * In other words, we have 4 types of CellName/CellNameType which correspond to the
 * 4 type of table layout that we need to distinguish:
 *   1. Simple (non-truly-composite) dense: this is the dynamic thrift CFs whose
 *      comparator is not composite.
 *   2. Composite dense: this is the dynamic thrift CFs with a CompositeType comparator.
 *   3. Simple (non-truly-composite) sparse: this is the thrift static CFs (that
 *      don't have a composite comparator).
 *   4. Composite sparse: this is the CQL3 layout (note that this is the only one that
 *      support collections).
 */
public interface CellNameType extends CType
{
    /**
     * Whether or not the cell names for this type are dense.
     */
    public boolean isDense();

    /**
     * The number of clustering columns for the table this is the type of.
     */
    public int clusteringPrefixSize();

    /**
     * A builder for the clustering prefix.
     */
    public CBuilder prefixBuilder();

    /**
     * Whether or not there is some collections defined in this type.
     */
    public boolean hasCollections();

    /**
     * Whether or not this type layout support collections.
     */
    public boolean supportCollections();

    /**
     * The type of the collections (or null if the type has not collections).
     */
    public ColumnToCollectionType collectionType();

    /**
     * Return the new type obtained by adding the new collection type for the provided column name
     * to this type.
     */
    public CellNameType addCollection(ColumnIdentifier columnName, CollectionType newCollection);

    /**
     * Returns a new CellNameType that is equivalent to this one but with one
     * of the subtype replaced by the provided new type.
     */
    @Override
    public CellNameType setSubtype(int position, AbstractType<?> newType);

    /**
     * Creates a row marker for the CQL3 having the provided clustering prefix.
     *
     * Note that this is only valid for CQL3 tables (isCompound() and !isDense()) and should
     * only be called for them.
     */
    public CellName rowMarker(Composite prefix);

    /**
     * Creates a new CellName given a clustering prefix and a CQL3 columnName.
     *
     * Note that for dense types, the columnName can be null.
     */
    public CellName create(Composite prefix, ColumnIdentifier columnName);

    /**
     * Creates a new collection CellName given a clustering prefix, a CQL3 columnName and the collection element.
     */
    public CellName create(Composite prefix, ColumnIdentifier columnName, ByteBuffer collectionElement);

    /**
     * Convenience method to create cell names given its components.
     *
     * This is equivalent to CType#make() but return a full cell name (and thus
     * require all the components of the name).
     */
    public CellName makeCellName(Object... components);

    /**
     * Deserialize a Composite from a ByteBuffer.
     *
     * This is equilvalent to CType#fromByteBuffer but assumes the buffer is a full cell
     * name. This is meant for thrift/cql2 to convert the fully serialized buffer we
     * get from the clients.
     */
    public CellName cellFromByteBuffer(ByteBuffer bb);

    /**
     * Creates a new CQL3Row builder for this type. See CQL3Row for details.
     */
    public CQL3Row.Builder CQL3RowBuilder(long now);

    // The two following methods are used to pass the declared regular column names (in CFMetaData)
    // to the CellNameType. This is only used for optimization sake, see SparseCellNameType.
    public void addCQL3Column(ColumnIdentifier id);
    public void removeCQL3Column(ColumnIdentifier id);

    /**
     * Creates a new Deserializer. This is used by AtomDeserializer to do incremental and on-demand
     * deserialization of the on disk atoms. See AtomDeserializer for details.
     */
    public Deserializer newDeserializer(DataInput in);

    /*
     * Same as in CType, follows a number of per-CellNameType instances for the Comparator and Serializer used
     * throughout the code (those that require full CellName versus just Composite).
     */

    // Ultimately, those might be split into an IVersionedSerializer and an ISSTableSerializer
    public ISerializer<CellName> cellSerializer();

    public Comparator<Cell> columnComparator();
    public Comparator<Cell> columnReverseComparator();
    public Comparator<OnDiskAtom> onDiskAtomComparator();

    public ColumnSerializer columnSerializer();
    public OnDiskAtom.Serializer onDiskAtomSerializer();
    public IVersionedSerializer<NamesQueryFilter> namesQueryFilterSerializer();
    public IVersionedSerializer<IDiskAtomFilter> diskAtomFilterSerializer();

    public interface Deserializer
    {
        /**
         * Whether this deserializer is done or not, i.e. whether we're reached the end of row marker.
         */
        public boolean hasNext() throws IOException;

        /**
         * Whether or not some name has been read but not consumed by readNext.
         */
        public boolean hasUnprocessed() throws IOException;

        /**
         * Comparare the next name to read to the provided Composite.
         * This does not consume the next name.
         */
        public int compareNextTo(Composite composite) throws IOException;

        /**
         * Actually consume the next name and return it.
         */
        public Composite readNext() throws IOException;

        /**
         * Skip the next name (consuming it).
         */
        public void skipNext() throws IOException;
    }
}
