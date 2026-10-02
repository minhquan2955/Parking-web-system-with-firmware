#pragma once
// Copy to parking_config.h (ignored by Git). Use the computer's LAN IPv4,
// not localhost. This transport is HTTP on a trusted demo LAN only.
#define PARKING_WIFI_SSID "YOUR_WIFI"
#define PARKING_WIFI_PASSWORD "YOUR_WIFI_PASSWORD"
#define PARKING_BACKEND_IP "192.168.1.117"
#define PARKING_BACKEND_PORT 3000
#define PARKING_DEVICE_ID "ESP32-01"
#define PARKING_DEVICE_KEY "COPY_DEVICE_KEY_FROM_PROJECT_ENV"
// Local OTA login, separate from website accounts. Stop the model before OTA.
#define PARKING_OTA_USER "admin"
#define PARKING_OTA_PASSWORD "CHANGE_THIS_OTA_PASSWORD"
