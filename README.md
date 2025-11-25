# TabCaster FrameClient - Android UDP Streaming Client For TabCaster

An open-source Android application for ultra-low latency screen streaming over UDP.
The client automatically discovers streaming servers on the local network and provides a seamless fullscreen streaming experience.

It is the companion application to TabCaster server. Both are required in order for you to use TabCaster.


## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Architecture](#architecture)
- [Dependencies](#dependencies)
- [Installation](#installation)
- [Usage](#usage)
- [API Documentation](#api-documentation)
- [Network Protocol](#network-protocol)
- [Configuration](#configuration)
- [Contributing](#contributing)
- [License](#license)

## Overview

FrameClient is designed for ultra-low latency screen streaming applications. It is the companion application to TabCaster Server, and is designed to act as an additional monitor for a Linux computer running TabCaster Server

It uses UDP for minimal network overhead and implements aggressive buffering strategies and damage tracking to achieve near real-time performance. The application automatically discovers compatible streaming servers using mDNS/DNS-SD and provides a simple interface for connection and playback.

## Features

- **Ultra-low latency streaming** (50-100ms buffer)
- **Manual server connection** via IP Address (on port 23532)
- **Fullscreen mode** during streaming
- **Clean disconnect handling**
- **Stable wireless connection**

### Upcoming

- **Automatic server discovery** via mDNS/DNS-SD
- **Connection retry logic** with timeout handling

## Architecture

TabCaster FrameClient consists of four main components:

1. **MainActivity**: Main co-ordinator for the application
2. **UIController**: Handler for the UI
3. **UDPHandler**: Handles network connections
4. *TBD* **NetworkDiscovery**: mDNS server discovery

## Dependencies

Add these dependencies to your `app/build.gradle`:

```gradle
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation("com.github.bumptech.glide:glide:5.0.5")
    annotationProcessor("com.github.bumptech.glide:compiler:5.0.5")
}
```

## Installation

1. Clone the repository
2. Open in Android Studio
3. Add the required dependencies
4. Add network permissions to `app/src/main/AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
```

5. Build and run the application

## Usage

### Basic Usage

To start a stream:

1. **Launch the app** - It will automatically start discovering servers
2. **Connect to a server**:
   - *TBD* Tap "Find Servers" to see discovered servers
   - Or enter IP manually and tap "Connect"
3. **Stream will start automatically**
4. **Tap the screen or the fullscreen button to enter fullscreen**

To disconnect:

1. **Tap the screen if in fullscreen to exit**
2. **Tap the disconnect button**

### Manual Connection

Enter server address, type in the IP:
- `192.168.1.100`

Port `23532` is always used to connect in TabCaster

---

# Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests if applicable
5. Submit a pull request

## Code Style

- Follow Kotlin conventions
- Use meaningful variable names
- Add comments for complex logic
- Handle exceptions appropriately

---

# License

This project is GNU General Public Licence v3. 

A copy of the licence can be found here:
https://www.gnu.org/licenses/gpl-3.0.en.html

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
