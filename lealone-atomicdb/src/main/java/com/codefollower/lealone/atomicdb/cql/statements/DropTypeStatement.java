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
package com.codefollower.lealone.atomicdb.cql.statements;

import com.codefollower.lealone.atomicdb.auth.Permission;
import com.codefollower.lealone.atomicdb.config.*;
import com.codefollower.lealone.atomicdb.cql.*;
import com.codefollower.lealone.atomicdb.db.marshal.*;
import com.codefollower.lealone.atomicdb.exceptions.*;
import com.codefollower.lealone.atomicdb.service.ClientState;
import com.codefollower.lealone.atomicdb.service.MigrationManager;
import com.codefollower.lealone.atomicdb.transport.messages.ResultMessage;

public class DropTypeStatement extends SchemaAlteringStatement
{
    private final ColumnIdentifier name;
    private final boolean ifExists;

    public DropTypeStatement(ColumnIdentifier name, boolean ifExists)
    {
        super();
        this.name = name;
        this.ifExists = ifExists;
    }

    public void checkAccess(ClientState state) throws UnauthorizedException, InvalidRequestException
    {
        // We may want a slightly different permission?
        state.hasAllKeyspacesAccess(Permission.DROP);
    }

    public void validate(ClientState state) throws RequestValidationException
    {
        UserType old = Schema.instance.userTypes.getType(name);
        if (old == null)
        {
            if (ifExists)
                return;
            else
                throw new InvalidRequestException(String.format("No user type named %s exists.", name));
        }

        // We don't want to drop a type unless it's not used anymore (mainly because
        // if someone drops a type and recreates one with the same name but different
        // definition with the previous name still in use, things can get messy).
        // We have two places to check: 1) other user type that can nest the one
        // we drop and 2) existing tables referencing the type (maybe in a nested
        // way).
        for (UserType ut : Schema.instance.userTypes.getAllTypes().values())
        {
            if (ut.name.equals(name.bytes))
                continue;
            if (isUsedBy(ut))
                throw new InvalidRequestException(String.format("Cannot drop user type %s as it is still used by user type %s", name, ut.asCQL3Type()));
        }

        for (KSMetaData ksm : Schema.instance.getKeyspaceDefinitions())
            for (CFMetaData cfm : ksm.cfMetaData().values())
                for (ColumnDefinition def : cfm.allColumns())
                    if (isUsedBy(def.type))
                        throw new InvalidRequestException(String.format("Cannot drop user type %s as it is still used by table %s.%s", name, cfm.ksName, cfm.cfName));
    }

    private boolean isUsedBy(AbstractType<?> toCheck) throws RequestValidationException
    {
        if (toCheck instanceof CompositeType)
        {
            CompositeType ct = (CompositeType)toCheck;

            if ((ct instanceof UserType) && name.bytes.equals(((UserType)ct).name))
                return true;

            // Also reach into subtypes
            for (AbstractType<?> subtype : ct.types)
                if (isUsedBy(subtype))
                    return true;
        }
        else if (toCheck instanceof ColumnToCollectionType)
        {
            for (CollectionType collection : ((ColumnToCollectionType)toCheck).defined.values())
                if (isUsedBy(collection))
                    return true;
        }
        else if (toCheck instanceof CollectionType)
        {
            if (toCheck instanceof ListType)
                return isUsedBy(((ListType)toCheck).elements);
            else if (toCheck instanceof SetType)
                return isUsedBy(((SetType)toCheck).elements);
            else
                return isUsedBy(((MapType)toCheck).keys) || isUsedBy(((MapType)toCheck).keys);
        }
        return false;
    }

    public ResultMessage.SchemaChange.Change changeType()
    {
        return ResultMessage.SchemaChange.Change.DROPPED;
    }

    @Override
    public String keyspace()
    {
        // Kind of ugly, but SchemaAlteringStatement uses that for notifying change, and an empty keyspace
        // there kind of make sense
        return "";
    }

    public void announceMigration() throws InvalidRequestException, ConfigurationException
    {
        UserType toDrop = Schema.instance.userTypes.getType(name);
        // Can be null with ifExists
        if (toDrop != null)
            MigrationManager.announceTypeDrop(toDrop);
    }
}
