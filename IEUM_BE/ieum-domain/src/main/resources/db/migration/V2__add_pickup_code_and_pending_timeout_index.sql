ALTER TABLE `users_orders`
    ADD COLUMN `pickup_code` varchar(6) DEFAULT NULL AFTER `quantity`,
    ADD KEY `idx_users_orders_state_created` (`order_state`, `created_at`);
