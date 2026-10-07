# Cyrllic-ostavka regression test

Regression test for issue #103.

The input contains 1000 identical UTF-8 CSV rows:

    foo,оставка

Running `query.tarql` must produce the triple in `expected.nt` 1000 times.

The output must not contain the Unicode replacement character `�` (U+FFFD).
