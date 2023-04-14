#! /usr/bin/env python
import os, sys, yaml, json

sys.path.append(os.path.dirname(os.path.abspath(__file__)))
data_path = os.path.dirname(os.path.abspath(__file__)) + r'\..\..\..\data\init_data'


# parse datasource from config.yaml
def get_sources():
    datasources = {}
    tables = []
    for f in os.listdir(data_path):
        if not f.endswith(".py"):
            continue
        tables.append(f.split(".")[0])

    with open(os.path.dirname(os.path.abspath(__file__)) + "/../../../config/config.yaml", "r") as fd:
        sources = yaml.safe_load(fd)
        for source in sources:
            config = sources[source]
            if "connector" not in config:
                continue
            datasources[source] = {
                "tables": tables,
                "config": config,
            }
    return datasources


if __name__ == "__main__":
    print(json.dumps(get_sources(), indent=4))

