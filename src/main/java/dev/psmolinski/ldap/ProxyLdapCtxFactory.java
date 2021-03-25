package dev.psmolinski.ldap;

import javax.naming.CommunicationException;
import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;
import javax.naming.spi.InitialContextFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Hashtable;
import java.util.Map;

/**
 * This is a workaround for a common cloud problem where a TCP/IP connection can be closed
 * when there is no traffic on the link. The factory creates a proxy object that can be
 * used for a limited time since the last access. If the timeout is exceeded the old context
 * is closed and a fresh one is established.
 * <br/>
 * To use this class set the properties:
 * <pre>java.naming.factory.initial=dev.psmolinski.ldap.ProxyLdapCtxFactory
 * com.sun.jndi.ldap.idle.timeout=180000
 * com.sun.jndi.ldap.idle.refresh=false</pre>
 */
public class ProxyLdapCtxFactory implements InitialContextFactory {

    public Context getInitialContext(Hashtable<?,?> environment) throws NamingException {

        LdapCtxHolder holder = new LdapCtxHolder(
                sanitize(environment),
                getIdleTimeout(environment),
                getIdleRefresh(environment));

        return (LdapContext)Proxy.newProxyInstance(
                ClassLoader.getSystemClassLoader(),
                new Class[]{LdapContext.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        holder.close();
                        return null;
                    }
                    try {
                        return method.invoke(holder.getContext(), args);
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                }
        );

    }

    private static long getIdleTimeout(Hashtable<?,?> environment) {
        String idleTimeoutStr = (String)environment.get("com.sun.jndi.ldap.idle.timeout");
        if (idleTimeoutStr==null) idleTimeoutStr = "600000"; // 10 minutes
        return Long.parseLong(idleTimeoutStr);
    }

    private static boolean getIdleRefresh(Hashtable<?,?> environment) {
        String idleRefreshStr = (String)environment.get("com.sun.jndi.ldap.idle.refresh");
        if (idleRefreshStr==null) idleRefreshStr = "false";
        return Boolean.valueOf(idleRefreshStr);
    }

    private static Hashtable<Object,Object> sanitize(Hashtable<?,?> environment) {
        Hashtable<Object,Object> _environment = new Hashtable<>();
        for (Map.Entry<?,?> e : environment.entrySet()) {
            if (Context.INITIAL_CONTEXT_FACTORY.equals(e.getKey())) {
                _environment.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
                continue;
            }
            if ("com.sun.jndi.ldap.idle.timeout".equals(e.getKey())) {
                continue;
            }
            if ("com.sun.jndi.ldap.idle.refresh".equals(e.getKey())) {
                continue;
            }
            _environment.put(e.getKey(), e.getValue());
        }
        return _environment;
    }

    private static class LdapCtxHolder {

        private final Hashtable<?,?> environment;
        private final long idleTimeout;
        private final boolean idleRefresh;

        private LdapContext context;
        private long lastAccess;

        /**
         * Initialize the context and create the first context object. It is important to create
         * the context, because the context creation is used to verify the bindDN / password.
         * @param environment target environment passed to the underlying context factory
         * @param idleTimeout time how long the connection is usable since the last access
         * @param idleRefresh flag whether the connection should be recreated or just closed
         */
        public LdapCtxHolder(Hashtable<?,?> environment, long idleTimeout, boolean idleRefresh) throws NamingException {

            this.environment = environment;
            this.idleTimeout = idleTimeout;
            this.idleRefresh = idleRefresh;

            context = new InitialLdapContext(environment, null);
            lastAccess = System.currentTimeMillis();

        }

        /**
         * Retrieve the underlying LDAP context. If the last access time was more than
         * {@link #idleTimeout} milliseconds ago, the current connection is no longer available.
         * In such case the connection is closed, and depending on the {@link #idleRefresh} value,
         * the connection is recreated or {@link CommunicationException} is thrown indicating
         * that the current connection is no longer usable.
         */
        public synchronized LdapContext getContext() throws NamingException {

            long now = System.currentTimeMillis();
            long idleAge = now-lastAccess;

            if (context!=null && idleAge < idleTimeout) {
                lastAccess = now;
                return context;
            }

            if (context!=null) {
                context.close();
                context = null;
            }

            if (!idleRefresh) {
                throw new CommunicationException("LDAP Context idle for "+idleAge+"ms");
            }

            context = new InitialLdapContext(environment, null);
            lastAccess = now;
            return context;

        }

        /**
         * In case of LDAP closing of a context object does not bring it to the terminal state.
         * This means the context could be reconnected.
         */
        public synchronized void close() throws NamingException {
            if (this.context==null) return;
            this.context.close();
            this.context = null;
        }

    }

}
