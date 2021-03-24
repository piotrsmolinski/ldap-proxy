# LDAP Proxy

The component addresses the problem of a long running LDAP connection in the cloud.
If the connection is handled by some cloud router, 
the TCP/IP link could be closed after some time of no data transferred.
The issue was observed setting LDAP connections in Confluent Platform deployed in the cloud 
and accessing LDAP in private datacenter.
The idea behind this component is that if the underlying link could be terminated, 
do not use the existing connection anymore and force creation of a new one.

The timeouts are typically around 3-5 minutes of inactivity.
If an operation is executed against such connection, the client sends the request frame, 
but the response frame never comes back.
As a result a LDAP timeout is observed.
This timeout is set as `com.sun.jndi.ldap.read.timeout` configuration entry.
In Confluent Platform components it is set to `30000` by default.

To use this component with Confluent Platform, set the following properties:

```
ldap.java.naming.factory.initial = dev.psmolinski.ldap.ProxyLdapCtxFactory
ldap.com.sun.jndi.ldap.idle.timeout = 180000
ldap.com.sun.jndi.ldap.idle.refresh = false
```

With the settings above, if a connection is idle for more than 3 minutes,
the first operation will forcibly terminate it and throw `CommunicationException`.
The calling component interprets it as a disconnection and retries with a fresh context. 
