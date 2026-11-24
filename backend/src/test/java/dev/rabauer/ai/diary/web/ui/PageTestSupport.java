package dev.rabauer.ai.diary.web.ui;

import io.restassured.response.Response;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;

/** Helpers shared by the page tests: fetch a page, count elements, and post a no-JavaScript form with its token. */
final class PageTestSupport {

    private static final Pattern TOKEN = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    private PageTestSupport() {
    }

    static Response get(String path, String lang) {
        return given().header("Accept-Language", lang).get(path).then().statusCode(200).contentType("text/html").extract().response();
    }

    static String html(String path) {
        return get(path, "en").asString();
    }

    static int count(String haystack, String needle) {
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(haystack);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** The session cookie and form token a browser would get from {@code path}. */
    record Session(String cookie, String token) {
    }

    static Session session(String path) {
        Response r = get(path, "en");
        Matcher m = TOKEN.matcher(r.asString());
        if (!m.find()) {
            throw new IllegalStateException("no form token on " + path);
        }
        return new Session(r.getCookie("diary-csrf"), m.group(1));
    }
}
