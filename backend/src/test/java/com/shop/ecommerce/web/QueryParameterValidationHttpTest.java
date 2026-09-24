package com.shop.ecommerce.web;

import com.shop.ecommerce.dto.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Query-parameter validation on the list endpoints.
 *
 * <h2>★ Why this class exists</h2>
 *
 * <p>These are regression tests for a bug that was live for the whole life of the project and
 * that the existing suite could not have caught, because the flaw was in the <b>absence</b> of
 * behaviour rather than its presence.
 *
 * <p>{@code ProductFilter} and {@code OrderFilter} declare constraints - {@code @Min(0)} on
 * {@code page}, {@code @Min(1) @Max(100)} on {@code size}, a {@code @Pattern} on {@code sort}.
 * The list endpoints declare their parameters individually (so Swagger renders each one with
 * its own description) and then build the record by hand:
 *
 * <pre>{@code
 * ProductFilter filter = new ProductFilter(page, size, keyword, ...);
 * }</pre>
 *
 * <p><b>Bean Validation does not run on a plain constructor call.</b> Constraints are
 * evaluated when an object is bound by Spring, passed through {@code @Valid} on a request
 * body, or handed to a {@code Validator}. A hand-built record is none of those, so every
 * constraint on it was inert.
 *
 * <p>The consequence was not theoretical. {@code ?size=1000000} was accepted and forwarded to
 * {@code PageRequest.of(...)}, asking MySQL for a million rows - while the field's own comment
 * said the cap existed to prevent exactly that, and the endpoint's {@code @ApiResponse}
 * advertised a 400 for invalid input. <b>The documentation and the behaviour disagreed, and
 * the documentation was the one telling the truth about intent.</b>
 *
 * <h2>Why the existing tests passed anyway</h2>
 *
 * <p>{@code DtoValidationTest} validates the records directly with a {@code Validator}. That
 * proves the constraints are <i>correct</i>; it cannot prove they are <i>enforced</i>, because
 * it supplies the very invocation the controller was missing. {@code SortValidatorTest} covers
 * the sort whitelist, but that check lives in the service and is called explicitly, so it was
 * never affected.
 *
 * <p>The gap was structural: every test either validated the record in isolation or exercised
 * a service, and none went through <b>controller binding and construction</b> - the one step
 * where the constraints were being dropped.
 *
 * <h2>How to read a failure here</h2>
 *
 * <p>A failure in this class means a constraint has stopped being enforced at the HTTP layer.
 * Do not fix it by loosening the assertion: check that the controller still calls
 * {@code validateFilter}. If someone removes that call while they are "simplifying" the
 * method, these tests are the only thing that will notice.
 */
@DisplayName("HTTP - query parameter validation")
class QueryParameterValidationHttpTest extends HttpIntegrationTestSupport {

    // =================================================================
    //  The product list
    // =================================================================

    @Nested
    @DisplayName("GET /api/products")
    class ProductList {

        @Test
        @DisplayName("size above the maximum is a 400, not a request for a million rows")
        void sizeAboveMaximumIsRejected() throws Exception {
            mockMvc.perform(get("/api/products").param("size", "1000000"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(ErrorCode.VALIDATION_FAILED));
        }

        @Test
        @DisplayName("the rejection names `size`, so a client can highlight the field")
        void sizeRejectionNamesTheField() throws Exception {
            mockMvc.perform(get("/api/products").param("size", "1000"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
        }

        @Test
        @DisplayName("the boundary is inclusive: 100 is accepted, 101 is not")
        void theBoundaryIsNotOffByOne() throws Exception {
            mockMvc.perform(get("/api/products").param("size", "100"))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/products").param("size", "101"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("size below the minimum is rejected")
        void sizeBelowMinimumIsRejected() throws Exception {
            mockMvc.perform(get("/api/products").param("size", "0"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(ErrorCode.VALIDATION_FAILED));
        }

        @Test
        @DisplayName("a negative page index is rejected")
        void negativePageIsRejected() throws Exception {
            mockMvc.perform(get("/api/products").param("page", "-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("page"));
        }

        @Test
        @DisplayName("page 0 is accepted - the default is not accidentally excluded")
        void pageZeroIsAccepted() throws Exception {
            mockMvc.perform(get("/api/products").param("page", "0"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("an unsortable field is rejected")
        void unsortableFieldIsRejected() throws Exception {
            mockMvc.perform(get("/api/products").param("sort", "password,desc"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a sort expression that is not `field` or `field,dir` is rejected")
        void malformedSortExpressionIsRejected() throws Exception {
            mockMvc.perform(get("/api/products").param("sort", "price,sideways"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("no parameters at all is still a 200 - every one of them is optional")
        void noParametersIsValid() throws Exception {
            // The whole point of the compact constructor's defaults: absent must not fail
            // @Min(0) on a null page. If this ever breaks, validation has been moved ahead
            // of defaulting.
            mockMvc.perform(get("/api/products"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("a blank keyword is treated as no keyword, not as an error")
        void blankKeywordIsNormalised() throws Exception {
            mockMvc.perform(get("/api/products").param("keyword", "   "))
                    .andExpect(status().isOk());
        }
    }

    // =================================================================
    //  The order lists
    // =================================================================

    @Nested
    @DisplayName("the order list endpoints")
    class OrderLists {

        @Test
        @DisplayName("GET /api/orders rejects an oversized page before checking the caller")
        void myOrdersRejectsOversizedPage() throws Exception {
            // Unauthenticated on purpose. The validation runs before the service, but this
            // endpoint is behind the chain, so the expected answer is 401 - and that is worth
            // pinning: it shows validation does NOT become a way to probe the endpoint
            // anonymously. The parameter-validation path itself is covered below.
            mockMvc.perform(get("/api/orders").param("size", "1000000"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("GET /api/orders rejects a negative page for an authenticated caller")
        void myOrdersRejectsNegativePage() throws Exception {
            mockMvc.perform(get("/api/orders")
                            .param("page", "-1")
                            .header("Authorization", bearerFor(persistCustomer())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("page"));
        }

        @Test
        @DisplayName("GET /api/orders rejects an oversized page for an authenticated caller")
        void myOrdersRejectsOversizedPageWhenAuthenticated() throws Exception {
            mockMvc.perform(get("/api/orders")
                            .param("size", "1000000")
                            .header("Authorization", bearerFor(persistCustomer())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(ErrorCode.VALIDATION_FAILED));
        }

        @Test
        @DisplayName("GET /api/admin/orders rejects an oversized page for an admin")
        void allOrdersRejectsOversizedPage() throws Exception {
            mockMvc.perform(get("/api/admin/orders")
                            .param("size", "1000000")
                            .header("Authorization", bearerFor(persistAdmin())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(ErrorCode.VALIDATION_FAILED));
        }

        @Test
        @DisplayName("both order list endpoints enforce the same cap")
        void bothOrderEndpointsAgree() throws Exception {
            /*
             * They share a Filter record and a helper, but they are separate methods with
             * separate bodies - so without this test one of them can quietly drift. The bug
             * that motivated this class existed on BOTH endpoints, which is the argument for
             * asserting both rather than one and assuming.
             */
            int mine = mockMvc.perform(get("/api/orders")
                            .param("size", "101")
                            .header("Authorization", bearerFor(persistCustomer())))
                    .andReturn().getResponse().getStatus();

            int all = mockMvc.perform(get("/api/admin/orders")
                            .param("size", "101")
                            .header("Authorization", bearerFor(persistAdmin())))
                    .andReturn().getResponse().getStatus();

            assertThat(mine).as("GET /api/orders").isEqualTo(400);
            assertThat(all).as("GET /api/admin/orders").isEqualTo(400);
        }

        @Test
        @DisplayName("a default request is still valid, so the cap did not break the happy path")
        void defaultRequestIsValid() throws Exception {
            mockMvc.perform(get("/api/orders")
                            .header("Authorization", bearerFor(persistCustomer())))
                    .andExpect(status().isOk());
        }
    }
}
