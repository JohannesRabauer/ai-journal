package dev.rabauer.ai.diary.support;

import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

/** Adds the write-guard header to every RestAssured request unless the request sets it itself. */
public class ApiHeaderFilter implements Filter {

    @Override
    public Response filter(FilterableRequestSpecification req, FilterableResponseSpecification res, FilterContext ctx) {
        if (!req.getHeaders().hasHeaderWithName("X-AI-Diary")) {
            req.header("X-AI-Diary", "1");
        }
        return ctx.next(req, res);
    }
}
