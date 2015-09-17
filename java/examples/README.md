This repo contains examples of applications built using RecordService client APIs.

- `RSCat`: output tabular data for any data set readable by RecordService

- `SumQueryBenchmark`: This is an example of running a simple sum over a column,
  pushing the scan to RecordService.

- `Terasort`: terasort ported to RecordService. See README in package for more
  details. This also demonstrates how to implement a custom InputFormat using
  the RecordService APIs.

- `MapredColorCount`/`MapreduceAgeCount`/`MapReduceColorCount`: These are the examples
  ported from Apache Avro and demonstrate the steps required to port an existing
  Avro-based MapReduce job to use RecordService.

- `RecordCount`/`Wordcount`: More simple MapReduce applications that demonstrate some
  of the other InputFormats that are included in the client library.

- `com.cloudera.recordservice.examples.avro`: Unmodified from the [Apache Avro](https://avro.apache.org/) examples.
  We've included these to simplify sample data generation.