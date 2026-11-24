package dev.rabauer.ai.diary.security;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Test-only no-JS form route, the pattern future pages follow: declare {@code _csrf}, call {@code csrf.require}. */
@Path("/test-form")
public class TestFormResource {

    public static volatile int stored;

    @CheckedTemplate
    static class Templates {
        static native TemplateInstance form();
    }

    @Inject
    Csrf csrf;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance form() {
        return Templates.form();
    }

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_PLAIN)
    public String post(@FormParam("note") String note, @FormParam("_csrf") String token) {
        csrf.require(token);
        stored++;
        return "stored";
    }

    /** Forgot the token: the guard must refuse it before it runs. */
    @POST
    @Path("/undeclared")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_PLAIN)
    public String undeclared(@FormParam("note") String note) {
        stored++;
        return "stored";
    }
}
