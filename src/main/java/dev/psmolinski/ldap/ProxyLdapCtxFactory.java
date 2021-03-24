package dev.psmolinski.ldap;

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
 * com.sun.jndi.ldap.idle.timeout=180000</pre>
 */
public class ProxyLdapCtxFactory implements InitialContextFactory {

    public Context getInitialContext(Hashtable<?,?> environment) throws NamingException {

        LdapCtxHolder holder = new LdapCtxHolder(sanitize(environment), getIdleTimeout(environment));

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
            _environment.put(e.getKey(), e.getValue());
        }
        return _environment;
    }

    private static class LdapCtxHolder {

        private final Hashtable<?,?> environment;
        private final long idleTimeout;

        private LdapContext context;
        private long lastAccess;

        /**
         * Initialize the context and create the first context object. It is important to create
         * the context, because the context creation is used to verify the bindDN / password.
         */
        public LdapCtxHolder(Hashtable<?,?> environment, long idleTimeout) throws NamingException {

            this.environment = environment;
            this.idleTimeout = idleTimeout;

            context = new InitialLdapContext(environment, null);
            lastAccess = System.currentTimeMillis();

        }

        /**
         * Retrieve the underlying LDAP context. When the last one was closed or the the time since
         * last access is longer than the declared idle timeout, a new connection is created.
         */
        public synchronized LdapContext getContext() throws NamingException {

            if (context!=null && lastAccess<System.currentTimeMillis()+idleTimeout) {
                lastAccess = System.currentTimeMillis();
                return context;
            }

            if (context!=null) {
                context.close();
                context = null;
            }

            context = new InitialLdapContext(environment, null);
            lastAccess = System.currentTimeMillis();
            return context;

        }

        /**
         * In case of LDAP closing of a context object does not bring it to the terminal state.
         */
        public synchronized void close() throws NamingException {
            if (this.context==null) return;
            this.context.close();
            this.context = null;
        }

    }

}
