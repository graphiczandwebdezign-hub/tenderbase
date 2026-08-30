# Setup Gradle Wrapper - Fix for Failing Build

The GitHub Actions workflow is failing because the Gradle wrapper JAR file is missing from the repository.

## Steps to Fix (Run Locally)

1. **Navigate to the Android directory:**
   ```bash
   cd android
   ```

2. **Generate the Gradle wrapper:**
   ```bash
   gradle wrapper --gradle-version=8.7
   ```
   
   If you don't have Gradle installed globally, you can skip this step and use the existing `gradlew` script. The wrapper should generate the missing JAR.

3. **Update `.gitignore` to allow the wrapper JAR:**
   
   Edit `android/.gitignore` and ensure the following line is **removed or commented out**:
   ```
   # gradle/wrapper/gradle-wrapper.jar
   ```
   
   Currently, the `.gitignore` doesn't explicitly exclude it, so this should be fine.

4. **Verify the wrapper files exist:**
   ```bash
   ls -la gradle/wrapper/
   ```
   
   You should see:
   - `gradle-wrapper.jar`
   - `gradle-wrapper.properties`
   - `gradlew`
   - `gradlew.bat`

5. **Stage and commit the wrapper files:**
   ```bash
   git add gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties
   git add gradlew gradlew.bat
   git commit -m "Add Gradle wrapper - fixes GitHub Actions build"
   git push
   ```

6. **Re-run the workflow:**
   Go to GitHub Actions → "Build Android APK" → Run workflow

## Alternative: If Gradle is Not Installed

If you don't have Gradle installed, you can manually create the structure:

1. Clone the repository
2. Run the build from GitHub Actions, which will output better error details
3. Or, download gradle-wrapper.jar from an existing Android project using Gradle 8.7

## References

- [Gradle Wrapper Documentation](https://docs.gradle.org/current/userguide/gradle_wrapper.html)
- Your current Gradle version: 8.7 (from `gradle-wrapper.properties`)
