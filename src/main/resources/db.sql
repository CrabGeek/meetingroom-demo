CREATE TABLE IF NOT EXISTS `users` (
  `_id` varchar(64) NOT NULL,
  `openId` varchar(128) NOT NULL,
  `firstName` varchar(30) NOT NULL,
  `lastName` varchar(30) NOT NULL,
  `name` varchar(30) NOT NULL,
  `company` varchar(30) NOT NULL,
  `email` varchar(128) NOT NULL,
  `createdAt` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updatedAt` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`_id`),
  UNIQUE KEY `uk_users_openId` (`openId`),
  KEY `idx_users_name` (`name`),
  KEY `idx_users_company` (`company`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @drop_users_phone_sql = (
  SELECT IF(COUNT(*) = 1,
    'ALTER TABLE `users` DROP COLUMN `phone`',
    'SELECT 1'
  )
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'users'
    AND COLUMN_NAME = 'phone'
);
PREPARE drop_users_phone_stmt FROM @drop_users_phone_sql;
EXECUTE drop_users_phone_stmt;
DEALLOCATE PREPARE drop_users_phone_stmt;

SET @add_users_first_name_sql = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `users` ADD COLUMN `firstName` varchar(30) NOT NULL DEFAULT '''' AFTER `openId`',
    'SELECT 1'
  )
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'users'
    AND COLUMN_NAME = 'firstName'
);
PREPARE add_users_first_name_stmt FROM @add_users_first_name_sql;
EXECUTE add_users_first_name_stmt;
DEALLOCATE PREPARE add_users_first_name_stmt;

SET @add_users_last_name_sql = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `users` ADD COLUMN `lastName` varchar(30) NOT NULL DEFAULT '''' AFTER `firstName`',
    'SELECT 1'
  )
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'users'
    AND COLUMN_NAME = 'lastName'
);
PREPARE add_users_last_name_stmt FROM @add_users_last_name_sql;
EXECUTE add_users_last_name_stmt;
DEALLOCATE PREPARE add_users_last_name_stmt;

UPDATE `users`
SET `lastName` = SUBSTRING(`name`, 1, 1),
    `firstName` = SUBSTRING(`name`, 2)
WHERE `firstName` = ''
  AND `lastName` = ''
  AND CHAR_LENGTH(`name`) > 1;

CREATE TABLE IF NOT EXISTS `invite_codes` (
  `_id` varchar(64) NOT NULL,
  `code` varchar(6) NOT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `companyScope` text,
  `usedBy` text,
  `expiresAt` timestamp NULL DEFAULT NULL,
  `createdAt` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`_id`),
  UNIQUE KEY `uk_invite_codes_code` (`code`),
  KEY `idx_invite_codes_enabled` (`enabled`),
  KEY `idx_invite_codes_expiresAt` (`expiresAt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `rooms` (
  `_id` varchar(64) NOT NULL,
  `name` varchar(64) NOT NULL,
  `roomCapacity` int(11) NOT NULL DEFAULT 0,
  `enabled` tinyint(1) NOT NULL DEFAULT 1,
  `sortOrder` int(11) NOT NULL DEFAULT 0,
  `createdAt` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updatedAt` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`_id`),
  KEY `idx_rooms_enabled_sortOrder` (`enabled`, `sortOrder`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @add_room_capacity_sql = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE `rooms` ADD COLUMN `roomCapacity` int(11) NOT NULL DEFAULT 0 AFTER `name`',
    'SELECT 1'
  )
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'rooms'
    AND COLUMN_NAME = 'roomCapacity'
);
PREPARE add_room_capacity_stmt FROM @add_room_capacity_sql;
EXECUTE add_room_capacity_stmt;
DEALLOCATE PREPARE add_room_capacity_stmt;

CREATE TABLE IF NOT EXISTS `bookings` (
  `_id` varchar(64) NOT NULL,
  `roomId` varchar(64) NOT NULL,
  `roomName` varchar(64) NOT NULL,
  `date` varchar(10) NOT NULL,
  `startTime` varchar(5) NOT NULL,
  `endTime` varchar(5) NOT NULL,
  `title` varchar(100) NOT NULL,
  `organizerOpenId` varchar(128) NOT NULL,
  `organizerUserId` varchar(64) NOT NULL,
  `organizerName` varchar(30) NOT NULL,
  `organizerCompany` varchar(30) NOT NULL,
  `attendees` text NOT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'pending',
  `createdAt` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updatedAt` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`_id`),
  KEY `idx_bookings_room_date_status` (`roomId`, `date`, `status`),
  KEY `idx_bookings_organizer_status` (`organizerOpenId`, `status`),
  KEY `idx_bookings_date_startTime` (`date`, `startTime`),
  KEY `idx_bookings_organizerUser_status` (`organizerUserId`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `booking_subscriptions` (
  `_id` varchar(64) NOT NULL,
  `bookingId` varchar(64) NOT NULL,
  `openId` varchar(128) NOT NULL,
  `templateId` varchar(128) NOT NULL,
  `subscribed` tinyint(1) NOT NULL DEFAULT 1,
  `notifyStatus` varchar(20) NOT NULL DEFAULT 'pending',
  `sentAt` timestamp NULL DEFAULT NULL,
  `lastError` varchar(512) DEFAULT NULL,
  `retryCount` int(11) NOT NULL DEFAULT 0,
  `createdAt` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updatedAt` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`_id`),
  UNIQUE KEY `uk_booking_subscriptions_booking_openid_template` (`bookingId`, `openId`, `templateId`),
  KEY `idx_booking_subscriptions_status` (`notifyStatus`, `subscribed`, `createdAt`),
  KEY `idx_booking_subscriptions_openId` (`openId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `invite_codes` (`_id`, `code`, `enabled`, `companyScope`, `usedBy`)
VALUES ('inv_001', '123456', 1, '["万事网联","万事达卡"]', '[]')
ON DUPLICATE KEY UPDATE
  `enabled` = VALUES(`enabled`),
  `companyScope` = VALUES(`companyScope`);


INSERT INTO `rooms` (`_id`, `name`, `roomCapacity`, `enabled`, `sortOrder`)
VALUES
  ('201', '19F-HangZhou Room', 6, 1, 10),
  ('202', '19F-GuangZhou Room', 6, 1, 20),
  ('203', '19F-TianJin Room', 8, 1, 30),
  ('204', '19F-Training Room', 20, 1, 40)
ON DUPLICATE KEY UPDATE
  `name` = VALUES(`name`),
  `roomCapacity` = VALUES(`roomCapacity`),
  `enabled` = VALUES(`enabled`),
  `sortOrder` = VALUES(`sortOrder`);

UPDATE `bookings`
SET `roomName` = CASE `roomId`
  WHEN '201' THEN '19F-HangZhou Room'
  WHEN '202' THEN '19F-GuangZhou Room'
  WHEN '203' THEN '19F-TianJin Room'
  WHEN '204' THEN '19F-Training Room'
  ELSE `roomName`
END
WHERE `roomId` IN ('201', '202', '203', '204');