package com.shop.ecommerce.service;

import com.shop.ecommerce.dto.admin.DashboardStatsResponse;

/**
 * Aggregates for the admin dashboard.
 */
public interface AdminService {

    /** Every headline number the dashboard shows, in one request. */
    DashboardStatsResponse getDashboardStats();
}
