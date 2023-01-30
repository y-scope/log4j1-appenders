# log4j1-appenders

This package contains useful Log4j appenders. Currently, it contains:

* `ClpIrFileAppender` - Used to compress log events using 
  [CLP](https://github.com/y-scope/clp)'s IR stream format, allowing users to
  achieve higher compression than general-purpose compressors while the logs
  are being generated.

# Usage

1. Add the package and its dependencies to the `dependencies` section of your 
   your `pom.xml`:

   ```xml
   <dependencies>
     <!-- The appenders -->
     <dependency>
       <groupId>com.yscope.logging</groupId>
       <artifactId>log4j1-appenders</artifactId>
       <version>0.0.0</version>
     </dependency>
     <!-- Packages that log4j1-appenders depends on -->
     <dependency>
       <groupId>com.github.luben</groupId>
       <artifactId>zstd-jni</artifactId>
       <version>1.5.2-1</version>
     </dependency>
     <dependency>
       <groupId>log4j</groupId>
       <artifactId>log4j</artifactId>
       <version>1.2.17</version>
       <scope>provided</scope>
     </dependency>
   </dependencies>
   ```

2. Add the appender to your log4j configuration file. Here is a sample 
   log4j.properties file:

   ```properties
   log4j.rootLogger=INFO, clpir
   
   log4j.appender.clpir=com.yscope.logging.log4j1.ClpIrFileAppender
   log4j.appender.clpir.layout=org.apache.log4j.EnhancedPatternLayout
   log4j.appender.clpffi.layout.ConversionPattern=%d{yyyy-MM-dd HH:mm:ss.SSSZ} %p [%c{1}] %m%n
   log4j.appender.clpffi.file=logs.clp.zst
   log4j.appender.clpffi.useFourByteEncoding=true
   log4j.appender.clpffi.closeFrameOnFlush=false
   log4j.appender.clpffi.compressionLevel=3
   ```

# Providing Feedback

You can use GitHub issues to [report a bug](https://github.com/y-scope/log4j1-appenders/issues/new?assignees=&labels=bug&template=bug-report.yml)
or [request a feature](https://github.com/y-scope/log4j1-appenders/issues/new?assignees=&labels=enhancement&template=feature-request.yml).

# Building

* Build and test
  ```shell
  mvn package
  ```
* Build without any extras
  ```shell
  mvn package -DskipTests -Dmaven.javadoc.skip=true -Dmaven.source.skip
  ```

# Testing

```shell
mvn test
```
