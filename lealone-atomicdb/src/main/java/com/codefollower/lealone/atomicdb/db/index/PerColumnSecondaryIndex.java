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
package com.codefollower.lealone.atomicdb.db.index;

import java.nio.ByteBuffer;

import com.codefollower.lealone.atomicdb.db.Cell;
import com.codefollower.lealone.atomicdb.utils.FBUtilities;

/**
 * Base class for Secondary indexes that implement a unique index per column
 *
 */
public abstract class PerColumnSecondaryIndex extends SecondaryIndex
{
    /**
     * Delete a column from the index
     *
     * @param rowKey the underlying row key which is indexed
     * @param col all the column info
     */
    public abstract void delete(ByteBuffer rowKey, Cell col);

    /**
     * insert a column to the index
     *
     * @param rowKey the underlying row key which is indexed
     * @param col all the column info
     */
    public abstract void insert(ByteBuffer rowKey, Cell col);

    /**
     * update a column from the index
     *
     * @param rowKey the underlying row key which is indexed
     * @param col all the column info
     */
    public abstract void update(ByteBuffer rowKey, Cell col);

    public String getNameForSystemKeyspace(ByteBuffer column)
    {
        return getIndexName();
    }

    @Override
    public boolean validate(Cell cell)
    {
        return cell.value().remaining() < FBUtilities.MAX_UNSIGNED_SHORT;
    }
}
