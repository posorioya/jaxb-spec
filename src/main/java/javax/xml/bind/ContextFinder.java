/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2003-2015 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package javax.xml.bind;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.AccessController;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * This class is package private and therefore is not exposed as part of the
 * JAXB API.
 *
 * This code is designed to implement the JAXB 1.0 spec pluggability feature
 *
 * @author <ul><li>Ryan Shoemaker, Sun Microsystems, Inc.</li></ul>
 * @see JAXBContext
 */
class ContextFinder {

    /**
     * When JAXB is in J2SE, rt.jar has to have a JAXB implementation.
     * However, rt.jar cannot have META-INF/services/javax.xml.bind.JAXBContext
     * because if it has, it will take precedence over any file that applications have
     * in their jar files.
     *
     * <p>
     * When the user bundles his own JAXB implementation, we'd like to use it, and we
     * want the platform default to be used only when there's no other JAXB provider.
     *
     * <p>
     * For this reason, we have to hard-code the class name into the API.
     */
    private static final String PLATFORM_DEFAULT_FACTORY_CLASS = "com.sun.xml.internal.bind.v2.ContextFactory";

    // previous value of JAXBContext.JAXB_CONTEXT_FACTORY, using also this to ensure bacwards compatibility
    private static final String JAXB_CONTEXT_FACTORY_DEPRECATED = "javax.xml.bind.context.factory";

    private static final Logger logger;

    static {
        logger = Logger.getLogger("javax.xml.bind");
        try {
            if (AccessController.doPrivileged(new GetPropertyAction("jaxb.debug")) != null) {
                // disconnect the logger from a bigger framework (if any)
                // and take the matters into our own hands
                logger.setUseParentHandlers(false);
                logger.setLevel(Level.ALL);
                ConsoleHandler handler = new ConsoleHandler();
                handler.setLevel(Level.ALL);
                logger.addHandler(handler);
            } else {
                // don't change the setting of this logger
                // to honor what other frameworks
                // have done on configurations.
            }
        } catch (Throwable t) {
            // just to be extra safe. in particular System.getProperty may throw
            // SecurityException.
        }
    }

    private static ServiceLoaderUtil.ExceptionHandler<JAXBException> EXCEPTION_HANDLER = new ServiceLoaderUtil.ExceptionHandler<JAXBException>() {
        @Override
        public JAXBException createException(Throwable throwable, String message) {
            return new JAXBException(message, throwable);
        }
    };

    /**
     * If the {@link InvocationTargetException} wraps an exception that shouldn't be wrapped,
     * throw the wrapped exception.
     */
    private static void handleInvocationTargetException(InvocationTargetException x) throws JAXBException {
        Throwable t = x.getTargetException();
        if (t != null) {
            if (t instanceof JAXBException)
                // one of our exceptions, just re-throw
                throw (JAXBException) t;
            if (t instanceof RuntimeException)
                // avoid wrapping exceptions unnecessarily
                throw (RuntimeException) t;
            if (t instanceof Error)
                throw (Error) t;
        }
    }


    /**
     * Determine if two types (JAXBContext in this case) will generate a ClassCastException.
     *
     * For example, (targetType)originalType
     *
     * @param originalType
     *          The Class object of the type being cast
     * @param targetType
     *          The Class object of the type that is being cast to
     * @return JAXBException to be thrown.
     */
    private static JAXBException handleClassCastException(Class originalType, Class targetType) {
        final URL targetTypeURL = which(targetType);

        return new JAXBException(Messages.format(Messages.ILLEGAL_CAST,
                // we don't care where the impl class is, we want to know where JAXBContext lives in the impl
                // class' ClassLoader
                getClassClassLoader(originalType).getResource("javax/xml/bind/JAXBContext.class"),
                targetTypeURL));
    }

    /**
     * Create an instance of a class using the specified ClassLoader
     */
    static JAXBContext newInstance(String contextPath,
                                   String className,
                                   ClassLoader classLoader,
                                   Map properties) throws JAXBException {

        try {
            Class spFactory = ServiceLoaderUtil.safeLoadClass(className, PLATFORM_DEFAULT_FACTORY_CLASS, classLoader);
            return newInstance(contextPath, spFactory, classLoader, properties);
        } catch (ClassNotFoundException x) {
            throw new JAXBException(Messages.format(Messages.PROVIDER_NOT_FOUND, className), x);

        } catch (RuntimeException x) {
            // avoid wrapping RuntimeException to JAXBException,
            // because it indicates a bug in this code.
            throw x;
        } catch (Exception x) {
            // can't catch JAXBException because the method is hidden behind
            // reflection.  Root element collisions detected in the call to
            // createContext() are reported as JAXBExceptions - just re-throw it
            // some other type of exception - just wrap it
            throw new JAXBException(Messages.format(Messages.COULD_NOT_INSTANTIATE, className, x), x);
        }
    }

    static JAXBContext newInstance(String contextPath, Class spFactory, ClassLoader classLoader, Map properties) throws JAXBException {

        try {
            /*
             * javax.xml.bind.context.factory points to a class which has a
             * static method called 'createContext' that
             * returns a javax.xml.JAXBContext.
             */

            Object context = null;

            // first check the method that takes Map as the third parameter.
            // this is added in 2.0.
            try {
                Method m = spFactory.getMethod("createContext", String.class, ClassLoader.class, Map.class);
                // any failure in invoking this method would be considered fatal
                context = m.invoke(null, contextPath, classLoader, properties);
            } catch (NoSuchMethodException e) {
                // it's not an error for the provider not to have this method.
            }

            if (context == null) {
                // try the old method that doesn't take properties. compatible with 1.0.
                // it is an error for an implementation not to have both forms of the createContext method.
                Method m = spFactory.getMethod("createContext", String.class, ClassLoader.class);
                // any failure in invoking this method would be considered fatal
                context = m.invoke(null, contextPath, classLoader);
            }

            if (!(context instanceof JAXBContext)) {
                // the cast would fail, so generate an exception with a nice message
                throw handleClassCastException(context.getClass(), JAXBContext.class);
            }
            return (JAXBContext) context;
        } catch (InvocationTargetException x) {
            handleInvocationTargetException(x);
            // for other exceptions, wrap the internal target exception
            // with a JAXBException
            Throwable e = x;
            if (x.getTargetException() != null)
                e = x.getTargetException();

            throw new JAXBException(Messages.format(Messages.COULD_NOT_INSTANTIATE, spFactory, e), e);
        } catch (RuntimeException x) {
            // avoid wrapping RuntimeException to JAXBException,
            // because it indicates a bug in this code.
            throw x;
        } catch (Exception x) {
            // can't catch JAXBException because the method is hidden behind
            // reflection.  Root element collisions detected in the call to
            // createContext() are reported as JAXBExceptions - just re-throw it
            // some other type of exception - just wrap it
            throw new JAXBException(Messages.format(Messages.COULD_NOT_INSTANTIATE, spFactory, x), x);
        }
    }

    /**
     * Create an instance of a class using the thread context ClassLoader
     */
    static JAXBContext newInstance(Class[] classes, Map properties, String className) throws JAXBException {

        Class spi;
        try {
            spi = ServiceLoaderUtil.safeLoadClass(className, PLATFORM_DEFAULT_FACTORY_CLASS, getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new JAXBException(e);
        }

        if (logger.isLoggable(Level.FINE)) {
            // extra check to avoid costly which operation if not logged
            logger.log(Level.FINE, "loaded {0} from {1}", new Object[]{className, which(spi)});
        }

        return newInstance(classes, properties, spi);
    }

    static JAXBContext newInstance(Class[] classes,
                                   Map properties,
                                   Class spFactory) throws JAXBException {
        try {
            Method m = spFactory.getMethod("createContext", Class[].class, Map.class);
            Object context = m.invoke(null, classes, properties);
            if (!(context instanceof JAXBContext)) {
                // the cast would fail, so generate an exception with a nice message
                throw handleClassCastException(context.getClass(), JAXBContext.class);
            }
            return (JAXBContext) context;
        } catch (NoSuchMethodException e) {
            throw new JAXBException(e);
        } catch (IllegalAccessException e) {
            throw new JAXBException(e);
        } catch (InvocationTargetException e) {
            handleInvocationTargetException(e);

            Throwable x = e;
            if (e.getTargetException() != null)
                x = e.getTargetException();

            throw new JAXBException(x);
        }
    }

    static JAXBContext find(String factoryId, String contextPath, ClassLoader classLoader, Map properties) throws JAXBException {

        // TODO: do we want/need another layer of searching in $java.home/lib/jaxb.properties like JAXP?

        StringTokenizer packages = new StringTokenizer(contextPath, ":");
        if (!packages.hasMoreTokens()) {
            // no context is specified
            throw new JAXBException(Messages.format(Messages.NO_PACKAGE_IN_CONTEXTPATH));
        }

        // search for jaxb.properties in the class loader of each class first
        logger.fine("Searching jaxb.properties");
        while (packages.hasMoreTokens()) {
            // com.acme.foo - > com/acme/foo/jaxb.properties
            String factoryClassName = classNameFromPackageProperties(classLoader, packages.nextToken(":").replace('.', '/'), factoryId, JAXB_CONTEXT_FACTORY_DEPRECATED);
            if (factoryClassName != null) return newInstance(contextPath, factoryClassName, classLoader, properties);
        }

        String factoryName = classNameFromSystemProperties();
        if (factoryName != null) return newInstance(contextPath, factoryName, classLoader, properties);

        // TODO: SPEC change required! java.util.ServiceLoader
        JAXBContextFactory obj = ServiceLoaderUtil.firstByServiceLoader(JAXBContextFactory.class, logger, EXCEPTION_HANDLER);
        if (obj != null) return obj.createContext(contextPath, classLoader, properties);

        // to ensure backwards compatibility
        factoryName = firstByServiceLoaderDeprecated(JAXBContext.class, classLoader);
        if (factoryName != null) return newInstance(contextPath, factoryName, classLoader, properties);

        Class ctxFactory = (Class) ServiceLoaderUtil.lookupUsingOSGiServiceLoader("javax.xml.bind.JAXBContext", logger);
        if (ctxFactory != null) {
            return newInstance(contextPath, ctxFactory, classLoader, properties);
        }

        // else no provider found
        logger.fine("Trying to create the platform default provider");
        return newInstance(contextPath, PLATFORM_DEFAULT_FACTORY_CLASS, classLoader, properties);
    }

    static JAXBContext find(Class<?>[] classes, Map<String, ?> properties) throws JAXBException {

        // search for jaxb.properties in the class loader of each class first
        logger.fine("Searching jaxb.properties");
        for (final Class c : classes) {
            // this classloader is used only to load jaxb.properties, so doing this should be safe.
            if (c.getPackage() == null) continue;       // this is possible for primitives, arrays, and classes that are loaded by poorly implemented ClassLoaders

            // TODO: do we want to optimize away searching the same package?  org.Foo, org.Bar, com.Baz
            // classes from the same package might come from different class loades, so it might be a bad idea
            // TODO: it's easier to look things up from the class
            // c.getResourceAsStream("jaxb.properties");

            String factoryClassName = classNameFromPackageProperties(getClassClassLoader(c), c.getPackage().getName().replace('.', '/'), JAXBContext.JAXB_CONTEXT_FACTORY, JAXB_CONTEXT_FACTORY_DEPRECATED);
            if (factoryClassName != null) return newInstance(classes, properties, factoryClassName);
        }

        String factoryClassName = classNameFromSystemProperties();
        if (factoryClassName != null) return newInstance(classes, properties, factoryClassName);

        // TODO: SPEC change required! java.util.ServiceLoader
        JAXBContextFactory factory = ServiceLoaderUtil.firstByServiceLoader(JAXBContextFactory.class, logger, EXCEPTION_HANDLER);
        if (factory != null) return factory.createContext(classes, properties);

        // to ensure backwards compatibility
        String className = firstByServiceLoaderDeprecated(JAXBContext.class, getContextClassLoader());
        if (className != null) return newInstance(classes, properties, className);

        logger.fine("Trying to create the platform default provider");
        Class ctxFactoryClass = (Class) ServiceLoaderUtil.lookupUsingOSGiServiceLoader("javax.xml.bind.JAXBContext", logger);
        if (ctxFactoryClass != null) {
            return newInstance(classes, properties, ctxFactoryClass);
        }

        // else no provider found
        logger.fine("Trying to create the platform default provider");
        return newInstance(classes, properties, PLATFORM_DEFAULT_FACTORY_CLASS);
    }


    /**
     * first factoryId should be the preffered one, more of those can be provided to support backwards compatibility
     */
    private static String classNameFromPackageProperties(ClassLoader classLoader, String packageName, String ... factoryIds) throws JAXBException {
        String resourceName = packageName + "/jaxb.properties";
        logger.log(Level.FINE, "Trying to locate {0}", resourceName);
        Properties props = loadJAXBProperties(classLoader, resourceName);
        if (props != null) {
            for(String factoryId : factoryIds) {
                if (props.containsKey(factoryId)) {
                    return props.getProperty(factoryId);
                }
            }
            throw new JAXBException(Messages.format(Messages.MISSING_PROPERTY, packageName, factoryIds[0]));
        }
        return null;
    }

    private static String classNameFromSystemProperties() throws JAXBException {
        String factoryClassName = getSystemProperty(JAXBContext.JAXB_CONTEXT_FACTORY);
        if (factoryClassName != null) {
            return factoryClassName;
        }
        // leave this here to assure compatibility
        factoryClassName = getDeprecatedSystemProperty(JAXB_CONTEXT_FACTORY_DEPRECATED);
        if (factoryClassName != null) {
            return factoryClassName;
        }
        // leave this here to assure compatibility
        factoryClassName = getDeprecatedSystemProperty(JAXBContext.class.getName());
        if (factoryClassName != null) {
            return factoryClassName;
        }
        return null;
    }

    private static String getDeprecatedSystemProperty(String property) {
        String value = getSystemProperty(property);
        if (value != null) {
            logger.log(Level.WARNING, "Using non-standard property: {0}. Property {1} should be used instead.",
                    new Object[] {property, JAXBContext.JAXB_CONTEXT_FACTORY});
        }
        return value;
    }

    private static String getSystemProperty(String property) {
        logger.log(Level.FINE, "Checking system property {0}", property);
        String value = AccessController.doPrivileged(new GetPropertyAction(property));
        if (value != null) {
            logger.log(Level.FINE, "  found {0}", value);
        } else {
            logger.log(Level.FINE, "  not found");
        }
        return value;
    }

    private static Properties loadJAXBProperties(ClassLoader classLoader, String propFileName) throws JAXBException {

        Properties props = null;
        try {
            URL url;
            if (classLoader == null)
                url = ClassLoader.getSystemResource(propFileName);
            else
                url = classLoader.getResource(propFileName);

            if (url != null) {
                logger.log(Level.FINE, "loading props from {0}", url);
                props = new Properties();
                InputStream is = url.openStream();
                props.load(is);
                is.close();
            }
        } catch (IOException ioe) {
            logger.log(Level.FINE, "Unable to load " + propFileName, ioe);
            throw new JAXBException(ioe.toString(), ioe);
        }

        return props;
    }


    /**
     * Search the given ClassLoader for an instance of the specified class and
     * return a string representation of the URL that points to the resource.
     *
     * @param clazz
     *          The class to search for
     * @param loader
     *          The ClassLoader to search.  If this parameter is null, then the
     *          system class loader will be searched
     * @return
     *          the URL for the class or null if it wasn't found
     */
    static URL which(Class clazz, ClassLoader loader) {

        String classnameAsResource = clazz.getName().replace('.', '/') + ".class";

        if (loader == null) {
            loader = getSystemClassLoader();
        }

        return loader.getResource(classnameAsResource);
    }

    /**
     * Get the URL for the Class from it's ClassLoader.
     *
     * Convenience method for {@link #which(Class, ClassLoader)}.
     *
     * Equivalent to calling: which(clazz, clazz.getClassLoader())
     *
     * @param clazz
     *          The class to search for
     * @return
     *          the URL for the class or null if it wasn't found
     */
    static URL which(Class clazz) {
        return which(clazz, getClassClassLoader(clazz));
    }

    @SuppressWarnings("unchecked")
    private static ClassLoader getContextClassLoader() {
        if (System.getSecurityManager() == null) {
            return Thread.currentThread().getContextClassLoader();
        } else {
            return (ClassLoader) java.security.AccessController.doPrivileged(
                    new java.security.PrivilegedAction() {
                        public java.lang.Object run() {
                            return Thread.currentThread().getContextClassLoader();
                        }
                    });
        }
    }

    @SuppressWarnings("unchecked")
    private static ClassLoader getClassClassLoader(final Class c) {
        if (System.getSecurityManager() == null) {
            return c.getClassLoader();
        } else {
            return (ClassLoader) java.security.AccessController.doPrivileged(
                    new java.security.PrivilegedAction() {
                        public java.lang.Object run() {
                            return c.getClassLoader();
                        }
                    });
        }
    }

    private static ClassLoader getSystemClassLoader() {
        if (System.getSecurityManager() == null) {
            return ClassLoader.getSystemClassLoader();
        } else {
            return (ClassLoader) java.security.AccessController.doPrivileged(
                    new java.security.PrivilegedAction() {
                        public java.lang.Object run() {
                            return ClassLoader.getSystemClassLoader();
                        }
                    });
        }
    }

    // TODO: to be removed - SPEC change required
    //    ServiceLoaderUtil.firstByServiceLoaderDeprecated should be used instead.
    @Deprecated
    static String firstByServiceLoaderDeprecated(Class spiClass, ClassLoader classLoader) throws JAXBException {
        final String jaxbContextFQCN = spiClass.getName();

        logger.fine("Searching META-INF/services");

        // search META-INF services next
        BufferedReader r = null;
        final String resource = new StringBuilder().append("META-INF/services/").append(jaxbContextFQCN).toString();
        try {
            final InputStream resourceStream =
                    (classLoader == null) ?
                            ClassLoader.getSystemResourceAsStream(resource) :
                            classLoader.getResourceAsStream(resource);

            if (resourceStream != null) {
                r = new BufferedReader(new InputStreamReader(resourceStream, "UTF-8"));
                String factoryClassName = r.readLine();
                if (factoryClassName != null) {
                    factoryClassName = factoryClassName.trim();
                }
                r.close();
                logger.log(Level.FINE, "Configured factorty class:{0}", factoryClassName);
                return factoryClassName;
            } else {
                logger.log(Level.FINE, "Unable to load:{0}", resource);
                return null;
            }
        } catch (UnsupportedEncodingException e) {
            // should never happen
            throw new JAXBException(e);
        } catch (IOException e) {
            throw new JAXBException(e);
        } finally {
            try {
                if (r != null) {
                    r.close();
                }
            } catch (IOException ex) {
                logger.log(Level.SEVERE, "Unable to close resource: " + resource, ex);
            }
        }
    }

}
