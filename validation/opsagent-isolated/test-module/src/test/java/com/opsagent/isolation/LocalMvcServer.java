package com.opsagent.isolation;

import com.opsagent.common.web.GlobalExceptionHandler;
import java.net.InetAddress;
import java.net.URI;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Test-only loopback servlet host. No Spring Boot auto-configuration or application files. */
public final class LocalMvcServer implements AutoCloseable {
    private final AnnotationConfigWebApplicationContext context;
    private final WebServer server;

    public LocalMvcServer(Object controller) throws Exception {
        context = new AnnotationConfigWebApplicationContext();
        context.register(MvcOnly.class);
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("testedInternalController", controller);
            factory.registerSingleton("realOpsAgentErrorHandler", new GlobalExceptionHandler());
        });
        var factory = new TomcatServletWebServerFactory(0);
        factory.setAddress(InetAddress.getByName("127.0.0.1"));
        server = factory.getWebServer(servletContext -> {
            var registration = servletContext.addServlet("dispatcher", new DispatcherServlet(context));
            registration.setLoadOnStartup(1);
            registration.addMapping("/");
        });
        server.start();
    }

    public URI origin() {
        return URI.create("http://127.0.0.1:" + server.getPort() + "/");
    }

    @Override public void close() {
        server.stop();
        context.close();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class MvcOnly {}
}
