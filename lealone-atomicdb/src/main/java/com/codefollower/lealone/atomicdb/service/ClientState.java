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
package com.codefollower.lealone.atomicdb.service;

import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import com.codefollower.lealone.atomicdb.auth.*;
import com.codefollower.lealone.atomicdb.config.DatabaseDescriptor;
import com.codefollower.lealone.atomicdb.config.Schema;
import com.codefollower.lealone.atomicdb.db.Keyspace;
import com.codefollower.lealone.atomicdb.db.SystemKeyspace;
import com.codefollower.lealone.atomicdb.exceptions.AuthenticationException;
import com.codefollower.lealone.atomicdb.exceptions.InvalidRequestException;
import com.codefollower.lealone.atomicdb.exceptions.UnauthorizedException;
import com.codefollower.lealone.atomicdb.tracing.Tracing;
import com.codefollower.lealone.atomicdb.utils.Pair;
import com.codefollower.lealone.atomicdb.utils.SemanticVersion;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Sets;
import org.apache.commons.lang3.StringUtils;


/**
 * State related to a client connection.
 */
public class ClientState
{
    public static final SemanticVersion DEFAULT_CQL_VERSION = com.codefollower.lealone.atomicdb.cql.QueryProcessor.CQL_VERSION;

    private static final Set<IResource> READABLE_SYSTEM_RESOURCES = new HashSet<>();
    private static final Set<IResource> PROTECTED_AUTH_RESOURCES = new HashSet<>();

    // User-level permissions cache.
    private static final LoadingCache<Pair<AuthenticatedUser, IResource>, Set<Permission>> permissionsCache = initPermissionsCache();

    static
    {
        // We want these system cfs to be always readable since many tools rely on them (nodetool, cqlsh, bulkloader, etc.)
        String[] cfs =  new String[] { SystemKeyspace.LOCAL_CF,
                                       SystemKeyspace.PEERS_CF,
                                       SystemKeyspace.SCHEMA_KEYSPACES_CF,
                                       SystemKeyspace.SCHEMA_COLUMNFAMILIES_CF,
                                       SystemKeyspace.SCHEMA_COLUMNS_CF };
        for (String cf : cfs)
            READABLE_SYSTEM_RESOURCES.add(DataResource.columnFamily(Keyspace.SYSTEM_KS, cf));

        PROTECTED_AUTH_RESOURCES.addAll(DatabaseDescriptor.getAuthenticator().protectedResources());
        PROTECTED_AUTH_RESOURCES.addAll(DatabaseDescriptor.getAuthorizer().protectedResources());
    }

    // Current user for the session
    private volatile AuthenticatedUser user;
    private volatile String keyspace;

    private SemanticVersion cqlVersion;

    // isInternal is used to mark ClientState as used by some internal component
    // that should have an ability to modify system keyspace.
    private final boolean isInternal;

    // The remote address of the client - null for internal clients.
    private final SocketAddress remoteAddress;

    /**
     * Construct a new, empty ClientState for internal calls.
     */
    private ClientState()
    {
        this.isInternal = true;
        this.remoteAddress = null;
    }

    protected ClientState(SocketAddress remoteAddress)
    {
        this.isInternal = false;
        this.remoteAddress = remoteAddress;
        if (!DatabaseDescriptor.getAuthenticator().requireAuthentication())
            this.user = AuthenticatedUser.ANONYMOUS_USER;
    }

    /**
     * @return a ClientState object for internal C* calls (not limited by any kind of auth).
     */
    public static ClientState forInternalCalls()
    {
        return new ClientState();
    }

    /**
     * @return a ClientState object for external clients (thrift/native protocol users).
     */
    public static ClientState forExternalCalls(SocketAddress remoteAddress)
    {
        return new ClientState(remoteAddress);
    }

    public SocketAddress getRemoteAddress()
    {
        return remoteAddress;
    }

    public String getRawKeyspace()
    {
        return keyspace;
    }

    public String getKeyspace() throws InvalidRequestException
    {
        if (keyspace == null)
            throw new InvalidRequestException("no keyspace has been specified");
        return keyspace;
    }

    public void setKeyspace(String ks) throws InvalidRequestException
    {
        // Skip keyspace validation for non-authenticated users. Apparently, some client libraries
        // call set_keyspace() before calling login(), and we have to handle that.
        if (user != null && Schema.instance.getKSMetaData(ks) == null)
            throw new InvalidRequestException("Keyspace '" + ks + "' does not exist");
        keyspace = ks;
    }

    /**
     * Attempts to login the given user.
     */
    public void login(AuthenticatedUser user) throws AuthenticationException
    {
        if (!user.isAnonymous() && !Auth.isExistingUser(user.getName()))
           throw new AuthenticationException(String.format("User %s doesn't exist - create it with CREATE USER query first",
                                                           user.getName()));
        this.user = user;
    }

    public void hasAllKeyspacesAccess(Permission perm) throws UnauthorizedException
    {
        if (isInternal)
            return;
        validateLogin();
        ensureHasPermission(perm, DataResource.root());
    }

    public void hasKeyspaceAccess(String keyspace, Permission perm) throws UnauthorizedException, InvalidRequestException
    {
        hasAccess(keyspace, perm, DataResource.keyspace(keyspace));
    }

    public void hasColumnFamilyAccess(String keyspace, String columnFamily, Permission perm)
    throws UnauthorizedException, InvalidRequestException
    {
        hasAccess(keyspace, perm, DataResource.columnFamily(keyspace, columnFamily));
    }

    private void hasAccess(String keyspace, Permission perm, DataResource resource)
    throws UnauthorizedException, InvalidRequestException
    {
        validateKeyspace(keyspace);
        if (isInternal)
            return;
        validateLogin();
        preventSystemKSSchemaModification(keyspace, resource, perm);
        if (perm.equals(Permission.SELECT) && READABLE_SYSTEM_RESOURCES.contains(resource))
            return;
        if (PROTECTED_AUTH_RESOURCES.contains(resource))
            if (perm.equals(Permission.CREATE) || perm.equals(Permission.ALTER) || perm.equals(Permission.DROP))
                throw new UnauthorizedException(String.format("%s schema is protected", resource));
        ensureHasPermission(perm, resource);
    }

    public void ensureHasPermission(Permission perm, IResource resource) throws UnauthorizedException
    {
        for (IResource r : Resources.chain(resource))
            if (authorize(r).contains(perm))
                return;

        throw new UnauthorizedException(String.format("User %s has no %s permission on %s or any of its parents",
                                                      user.getName(),
                                                      perm,
                                                      resource));
    }

    private void preventSystemKSSchemaModification(String keyspace, DataResource resource, Permission perm) throws UnauthorizedException
    {
        // we only care about schema modification.
        if (!(perm.equals(Permission.ALTER) || perm.equals(Permission.DROP) || perm.equals(Permission.CREATE)))
            return;

        // prevent system keyspace modification
        if (Keyspace.SYSTEM_KS.equalsIgnoreCase(keyspace))
            throw new UnauthorizedException(keyspace + " keyspace is not user-modifiable.");

        // we want to allow altering AUTH_KS and TRACING_KS.
        Set<String> allowAlter = Sets.newHashSet(Auth.AUTH_KS, Tracing.TRACE_KS);
        if (allowAlter.contains(keyspace.toLowerCase()) && !(resource.isKeyspaceLevel() && perm.equals(Permission.ALTER)))
            throw new UnauthorizedException(String.format("Cannot %s %s", perm, resource));
    }

    public void validateLogin() throws UnauthorizedException
    {
        if (user == null)
            throw new UnauthorizedException("You have not logged in");
    }

    public void ensureNotAnonymous() throws UnauthorizedException
    {
        validateLogin();
        if (user.isAnonymous())
            throw new UnauthorizedException("You have to be logged in and not anonymous to perform this request");
    }

    public void ensureIsSuper(String message) throws UnauthorizedException
    {
        if (DatabaseDescriptor.getAuthenticator().requireAuthentication() && (user == null || !user.isSuper()))
            throw new UnauthorizedException(message);
    }

    private static void validateKeyspace(String keyspace) throws InvalidRequestException
    {
        if (keyspace == null)
            throw new InvalidRequestException("You have not set a keyspace for this session");
    }

    public void setCQLVersion(String str) throws InvalidRequestException
    {
        SemanticVersion version;
        try
        {
            version = new SemanticVersion(str);
        }
        catch (IllegalArgumentException e)
        {
            throw new InvalidRequestException(e.getMessage());
        }

        SemanticVersion cql3 = com.codefollower.lealone.atomicdb.cql.QueryProcessor.CQL_VERSION;

        if (version.isSupportedBy(cql3))
            cqlVersion = cql3;
        else
            throw new InvalidRequestException(String.format("Provided version %s is not supported by this server (supported: %s)",
                                                            version,
                                                            StringUtils.join(getCQLSupportedVersion(), ", ")));
    }

    public AuthenticatedUser getUser()
    {
        return user;
    }

    public SemanticVersion getCQLVersion()
    {
        return cqlVersion;
    }

    public static SemanticVersion[] getCQLSupportedVersion()
    {
        SemanticVersion cql3 = com.codefollower.lealone.atomicdb.cql.QueryProcessor.CQL_VERSION;

        return new SemanticVersion[]{ cql3 };
    }

    private static LoadingCache<Pair<AuthenticatedUser, IResource>, Set<Permission>> initPermissionsCache()
    {
        if (DatabaseDescriptor.getAuthorizer() instanceof AllowAllAuthorizer)
            return null;

        int validityPeriod = DatabaseDescriptor.getPermissionsValidity();
        if (validityPeriod <= 0)
            return null;

        return CacheBuilder.newBuilder().expireAfterWrite(validityPeriod, TimeUnit.MILLISECONDS)
                                        .build(new CacheLoader<Pair<AuthenticatedUser, IResource>, Set<Permission>>()
                                        {
                                            public Set<Permission> load(Pair<AuthenticatedUser, IResource> userResource)
                                            {
                                                return DatabaseDescriptor.getAuthorizer().authorize(userResource.left,
                                                                                                    userResource.right);
                                            }
                                        });
    }

    private Set<Permission> authorize(IResource resource)
    {
        // AllowAllAuthorizer or manually disabled caching.
        if (permissionsCache == null)
            return DatabaseDescriptor.getAuthorizer().authorize(user, resource);

        try
        {
            return permissionsCache.get(Pair.create(user, resource));
        }
        catch (ExecutionException e)
        {
            throw new RuntimeException(e);
        }
    }
}
