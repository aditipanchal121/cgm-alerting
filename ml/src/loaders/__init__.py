"""One module per data source, each exposing `load(...) -> pd.DataFrame` in
the canonical schema (see ../schema.py). vigil_export is the only one that
runs today; the public-dataset loaders are documented stubs pending DUA-gated
downloads (see ../../data/README.md)."""
