

# Issue #91 - --test should print all CONSTRUCT queries

Regression test for https://github.com/tarql/tarql/issues/91

## Problem

When a TARQL mapping contains multiple CONSTRUCT queries,

the --test option prints the CONSTRUCT template and computed

variables only for the first query.

## Test files

- input.csv: CSV data containing Alice and Bob.

- mapping.tarql: Two CONSTRUCT queries using different predicates.

## Run

mvn -q exec:java "-Dexec.mainClass=org.deri.tarql.tarql" "-Dexec.args=--test test/91-test-all-construct/mapping.tarql test/91-test-all-construct/input.csv"

## Expected result

The output must contain both CONSTRUCT templates:

- ex:name

- ex:identifier

Each template must be followed by its own SELECT result table,

containing the two input rows (Alice and Bob).

## Regression

Before the fix, only the first template and its result table

were printed.

After the fix, both templates and their result tables are printed.

## Verification

Manual CLI test: passed.

Maven tests: 108 run, 0 failures, 0 errors, 6 skipped.

Note: This external regression case is not automatically

executed by the Maven test suite.
