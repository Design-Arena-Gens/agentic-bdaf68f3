@echo off
set DIR=%~dp0
set APP_BASE_NAME=%~n0
set APP_HOME=%DIR%

set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

if defined JAVA_HOME (
    set JAVA_EXE=%JAVA_HOME%\bin\java.exe
    if exist "%JAVA_EXE%" goto execute
    echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME% 1>&2
    exit /b 1
) else (
    set JAVA_EXE=java.exe
)

:execute
"%JAVA_EXE%" -Xmx64m -Xms64m -cp "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
