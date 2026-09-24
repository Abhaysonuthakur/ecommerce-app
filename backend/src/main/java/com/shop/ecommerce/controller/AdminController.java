package com.shop.ecommerce.controller;

import com.shop.ecommerce.dto.admin.DashboardStatsResponse;
import com.shop.ecommerce.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin dashboard's aggregate endpoint.
 *
 * <h2>One request, not six</h2>
 *
 * <p>An admin landing on the dashboard needs nine numbers, all displayed together. Six
 * endpoints would mean six round trips and six independently-resolving spinners for data
 * that arrives on one screen - and a dashboard where the revenue tile is still loading
 * while the order count has been sitting there for a second looks broken.
 *
 * <p>One endpoint means one request and one loading state. The queries behind it are all
 * trivial counts and one aggregate; there is no cost to doing them together, and the
 * transaction gives the response a consistent snapshot - six separate requests could
 * straddle an order being placed and show a total that does not match the status breakdown
 * beside it.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin Dashboard", description = "Aggregate statistics for the admin console")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    /**
     * Headline statistics for the dashboard.
     *
     * <p>Protected by the URL rule on {@code /api/admin/**} and additionally by
     * {@code @PreAuthorize("hasRole('ADMIN')")} on the service implementation. Note that
     * this method itself has no annotation - the role check lives on
     * {@code AdminServiceImpl}, so any future caller of that service is refused too.
     * Annotations on controllers protect a route; annotations on services protect an
     * operation, and the operation is the thing that must not happen.
     *
     * <p>Every number's definition is documented on
     * {@link DashboardStatsResponse}. The one worth repeating: <b>{@code totalRevenue}
     * excludes CANCELLED orders and includes PENDING ones</b>. A revenue figure whose
     * definition is implicit is a figure two people will read differently.
     */
    @GetMapping("/dashboard/stats")
    @Operation(summary = "Dashboard statistics (admin)",
            description = """
                    Products (total and active), categories, orders, customers, revenue and a per-status order
                    breakdown, in one response. `totalRevenue` excludes CANCELLED orders and includes PENDING ones.
                    `ordersByStatus` always contains every status, with 0 for statuses that have no orders.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The aggregates",
                    content = @Content(schema = @Schema(implementation = DashboardStatsResponse.class))),
            @ApiResponse(responseCode = "403", description = "Not an admin")
    })
    public ResponseEntity<DashboardStatsResponse> getDashboardStats() {
        return ResponseEntity.ok(adminService.getDashboardStats());
    }
}
