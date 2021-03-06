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
package com.codefollower.lealone.atomicdb.cql.functions;

import java.nio.ByteBuffer;
import java.util.List;

import com.codefollower.lealone.atomicdb.db.marshal.AbstractType;
import com.codefollower.lealone.atomicdb.db.marshal.BytesType;
import com.codefollower.lealone.atomicdb.db.marshal.UTF8Type;

public abstract class BytesConversionFcts
{
    // Most of the XAsBlob and blobAsX functions are basically no-op since everything is
    // bytes internally. They only "trick" the type system.
    public static Function makeToBlobFunction(AbstractType<?> fromType)
    {
        String name = fromType.asCQL3Type() + "asblob";
        return new AbstractFunction(name, BytesType.instance, fromType)
        {
            public ByteBuffer execute(List<ByteBuffer> parameters)
            {
                return parameters.get(0);
            }
        };
    }

    public static Function makeFromBlobFunction(AbstractType<?> toType)
    {
        String name = "blobas" + toType.asCQL3Type();
        return new AbstractFunction(name, toType, BytesType.instance)
        {
            public ByteBuffer execute(List<ByteBuffer> parameters)
            {
                return parameters.get(0);
            }
        };
    }

    public static final Function VarcharAsBlobFct = new AbstractFunction("varcharasblob", BytesType.instance, UTF8Type.instance)
    {
        public ByteBuffer execute(List<ByteBuffer> parameters)
        {
            return parameters.get(0);
        }
    };

    public static final Function BlobAsVarcharFact = new AbstractFunction("blobasvarchar", UTF8Type.instance, BytesType.instance)
    {
        public ByteBuffer execute(List<ByteBuffer> parameters)
        {
            return parameters.get(0);
        }
    };
}
