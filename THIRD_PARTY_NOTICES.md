# Third-party notices

The application distribution ships the following libraries as separate JARs in `lib/`. Its accompanying `notices/` directory reproduces the license texts and component notices for these versions.

| Distributed library | License and notices |
| --- | --- |
| PostgreSQL JDBC Driver 42.7.14 | [BSD 2-Clause and embedded component inventory](notices/pgjdbc/LICENSE). The driver contains OnGres [scram-client 3.2](notices/pgjdbc/licenses/com.ongres.scram/scram-client-3.2/META-INF/LICENSE), [scram-common 3.2](notices/pgjdbc/licenses/com.ongres.scram/scram-common-3.2/META-INF/LICENSE), [saslprep 2.2](notices/pgjdbc/licenses/com.ongres.stringprep/saslprep-2.2/META-INF/LICENSE) and [stringprep 2.2](notices/pgjdbc/licenses/com.ongres.stringprep/stringprep-2.2/META-INF/LICENSE). |
| Flyway Core, Flyway Database PostgreSQL and Flyway Database CockroachDB 13.13.0 | [Red Gate copyright notice](notices/flyway-LICENSE.txt) and [Apache License 2.0](notices/Apache-2.0.txt). The two database modules inherit the license declared by the [Flyway 13.13.0 parent POM](https://repo.maven.apache.org/maven2/org/flywaydb/flyway-parent/13.13.0/flyway-parent-13.13.0.pom). |
| Jackson Core 2.23.0 | [Apache License 2.0](notices/Apache-2.0.txt) and [Jackson Core NOTICE](notices/jackson/core-NOTICE.txt). The JAR includes [FastDoubleParser](notices/jackson/FastDoubleParser-LICENSE.txt), [its third-party licenses](notices/jackson/FastDoubleParser-ThirdParty-LICENSE.txt) and [Schubfach](notices/jackson/Schubfach-LICENSE.txt). |
| Jackson Databind 2.23.0 and Jackson Annotations 2.23 | [Apache License 2.0](notices/Apache-2.0.txt) and [the NOTICE supplied with both JARs](notices/jackson/annotations-and-databind-NOTICE.txt). |
| Checker Framework Qualifiers 3.55.1 | [MIT license and copyright notice](notices/checker-qual-LICENSE.txt). |

Gradle 9.9.2 supplies the launch scripts in `bin/`. Each script retains its upstream copyright and license header. The source checkout also includes the Gradle Wrapper scripts and Wrapper JAR. [Apache License 2.0](notices/Apache-2.0.txt) · [License from the Wrapper JAR](notices/gradle-wrapper-LICENSE.txt)
