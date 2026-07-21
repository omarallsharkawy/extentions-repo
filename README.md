# Aniyomi & Anikku Extensions Repository

An extension repository for Aniyomi and Anikku Android applications.

## Installation

### Adding the Repository to Aniyomi / Anikku

Copy and paste the following Repository URL into the application settings under **Settings > Extensions > Extension Repositories**:

```
https://raw.githubusercontent.com/omarallsharkawy/extentions-repo/repo/index.min.json
```

---

## Development Guide

### Prerequisites

Ensure the Android SDK and Java Development Kit (JDK 17 or higher) are installed and configured in your environment variables:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
```

### Building Extensions

To compile extensions locally using the Gradle wrapper:

```bash
# Compile a specific extension module
./gradlew :src:<lang>:<extension_name>:compileReleaseKotlin
```

### Code Formatting

This project enforces Kotlin code formatting rules using Spotless:

```bash
# Check code formatting
./gradlew spotlessKotlinCheck

# Apply code formatting fixes
./gradlew spotlessKotlinApply
```

---

## License

Copyright (c) 2026 Aniyomi & Anikku Extensions Contributors. Licensed under the Apache License, Version 2.0.
