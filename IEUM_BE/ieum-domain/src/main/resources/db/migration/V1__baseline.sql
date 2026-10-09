CREATE TABLE `users` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `name` varchar(10) NOT NULL,
  `tel` varchar(11) NOT NULL,
  `email` varchar(100) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKnekwtc70sk6c2dofve0axwnhb` (`tel`),
  UNIQUE KEY `UK6dotkott2kjsp8vw4d0m25fb7` (`email`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE `users_account` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `updated_at` datetime(6) NOT NULL,
  `user_id` bigint NOT NULL,
  `nickname` varchar(20) NOT NULL,
  `uid` varchar(36) NOT NULL,
  `password` varchar(100) NOT NULL,
  `user_type` enum('ADMIN','BUSINESS_OWNER','CONSUMER') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKemn1dbb613o7335kojoe0fr9n` (`user_id`),
  UNIQUE KEY `UKops9w9p0rq4nnabfso9u96pec` (`nickname`),
  UNIQUE KEY `UK4xxb6sl762w9cp1q6o4bc2q7c` (`uid`),
  CONSTRAINT `FKtcaiipkfeuiabcwhje2iqwf14` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE `stores` (
  `close_at` time DEFAULT NULL,
  `open_at` time DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `shutdown_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_account_id` bigint NOT NULL,
  `name` varchar(20) NOT NULL,
  `uid` varchar(36) NOT NULL,
  `logo_img_url` varchar(255) DEFAULT NULL,
  `store_type` enum('BAKERY','BAR','CAFE','FAST_FOOD','FOOD','FOOD_INGREDIENTS') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKqlgorhyixo1gjxij56wjrklbe` (`uid`),
  KEY `FKmhe5ebimc9xh3s7t5s1giulpo` (`user_account_id`),
  CONSTRAINT `FKmhe5ebimc9xh3s7t5s1giulpo` FOREIGN KEY (`user_account_id`) REFERENCES `users_account` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE `stores_items` (
  `initial_quantity` int NOT NULL,
  `original_price` int NOT NULL,
  `remaining_quantity` int NOT NULL,
  `sale_price` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `last_order_time` datetime(6) NOT NULL,
  `store_id` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `version` bigint NOT NULL,
  `uid` varchar(36) NOT NULL,
  `name` varchar(50) NOT NULL,
  `item_img_url` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKhp46qoxb98nl4kja3d7j52p9a` (`uid`),
  KEY `FK7qus7io5jyccux5xfo9koblhx` (`store_id`),
  CONSTRAINT `FK7qus7io5jyccux5xfo9koblhx` FOREIGN KEY (`store_id`) REFERENCES `stores` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE `users_orders` (
  `order_price` int NOT NULL,
  `quantity` int NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `ready_at` datetime(6) DEFAULT NULL,
  `store_item_id` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_account_id` bigint NOT NULL,
  `version` bigint NOT NULL,
  `idempotency_key` varchar(64) DEFAULT NULL,
  `order_state` enum('APPROVED','CANCELED','EXPIRED','PENDING','PICKED_UP','READY_FOR_PICKUP') NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_users_orders_account_idempotency_key` (`user_account_id`,`idempotency_key`),
  KEY `FK7bopbnohmu08ou6g1o4ayqi7a` (`store_item_id`),
  CONSTRAINT `FK7bopbnohmu08ou6g1o4ayqi7a` FOREIGN KEY (`store_item_id`) REFERENCES `stores_items` (`id`),
  CONSTRAINT `FKa6tvnf9d5i563nj8tr1rg8nib` FOREIGN KEY (`user_account_id`) REFERENCES `users_account` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE `items_reviews` (
  `rating` int NOT NULL,
  `blind_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `store_id` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_order_id` bigint NOT NULL,
  `img_url` varchar(500) DEFAULT NULL,
  `content` varchar(1000) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKnip58kbjaxbrxoyv9r5tjybbr` (`user_order_id`),
  KEY `idx_items_reviews_store_id` (`store_id`),
  CONSTRAINT `FKbgfyihoq8iskrpkxxnd33o90o` FOREIGN KEY (`store_id`) REFERENCES `stores` (`id`),
  CONSTRAINT `FKh4qfrbu1iknqyfv4749vr2601` FOREIGN KEY (`user_order_id`) REFERENCES `users_orders` (`id`),
  CONSTRAINT `chk_items_reviews_rating` CHECK ((`rating` between 1 and 5))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE `review_reports` (
  `created_at` datetime(6) NOT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `item_review_id` bigint NOT NULL,
  `processed_at` datetime(6) DEFAULT NULL,
  `reporter_id` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `reason` varchar(500) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKq4u2r0van5pxtmxo5ijm8bvo9` (`item_review_id`),
  KEY `FKksvy4ry0pqvgxjnpty742ttpg` (`reporter_id`),
  CONSTRAINT `FKksvy4ry0pqvgxjnpty742ttpg` FOREIGN KEY (`reporter_id`) REFERENCES `users_account` (`id`),
  CONSTRAINT `FKq4u2r0van5pxtmxo5ijm8bvo9` FOREIGN KEY (`item_review_id`) REFERENCES `items_reviews` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE `business_registration` (
  `approve_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `disapprove_at` datetime(6) DEFAULT NULL,
  `id` bigint NOT NULL AUTO_INCREMENT,
  `store_id` bigint NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `registration_img_url` varchar(500) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKnjhd2b1v8jglpw7v7xd9g5l2q` (`store_id`),
  CONSTRAINT `FKnjhd2b1v8jglpw7v7xd9g5l2q` FOREIGN KEY (`store_id`) REFERENCES `stores` (`id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

