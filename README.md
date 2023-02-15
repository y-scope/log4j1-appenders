# log4j1-appenders

This package contains useful Log4j appenders. Currently, it contains:

* `ClpIrFileAppender` - Used to compress log events using 
  [CLP](https://github.com/y-scope/clp)'s IR stream format, allowing users to
  achieve higher compression than general-purpose compressors while the logs
  are being generated.

* `AbstractBufferedRollingFileAppender` - An abstract class which enforces an
  opinionated workflow, skeleton interfaces and hooks optimized towards
  buffered rolling file appender implementations with remote persistent
  storage. In addition, the abstract class implements verbosity-aware
  hard+soft timeout based log freshness policy.

* `AbstractClpirBufferedRollingFileAppender` - Provides size-based file
  rollover, log freshness guarantee and streaming compression offered in
  `ClpIrFileAppender`. 

# Usage

## `ClpIrFileAppender`
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
    # NOTE:
    # 1. This appender doesn't require a date conversion pattern in the 
    #    conversion pattern. This is because the CLP appender stores the 
    #    timestamp separately from the message. CLP's IR decoders will allow 
    #    users to specify their desired timestamp format when decoding the logs.
    # 2. If a date conversion pattern is added, it will be removed from the 
    #    conversion pattern. This may result in an ugly conversion pattern since 
    #    the spaces around the date pattern are not removed.
    log4j.appender.clpir.layout.ConversionPattern=%p [%c{1}] %m%n
    log4j.appender.clpir.file=logs.clp.zst
    # Use CLP's four-byte encoding for lower memory usage at the cost of some
    # compression ratio
    log4j.appender.clpir.useFourByteEncoding=true
    # closeFrameOnFlush:
    # - true: any data buffered by the compressor is immediately flushed to disk;
    #   frequent flushes may lower compression ratio significantly
    # - false: any compressed data that is ready for writing will be flushed to disk
    log4j.appender.clpir.closeFrameOnFlush=false
    # compressionLevel: Higher compression levels may increase compression ratio
    # but will slow down compression. Valid compression levels are 1-19.
    log4j.appender.clpir.compressionLevel=3
   ```
   
## `AbstractClpIrBufferedRollingFileAppender`
To use class, we expect user to implement at minimum the `sync()` method to
perform file upload to remote persistent store.

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
