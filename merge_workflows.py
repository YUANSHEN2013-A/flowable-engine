import os

files = [
    'postgres.yml',
    'mysql.yml',
    'mariadb.yml',
    'oracle.yml',
    'sql-server.yml',
    'db2.yml'
]

jobs_content = ""

for f in files:
    path = os.path.join('/app/flowable-engine/.github/workflows', f)
    with open(path, 'r') as file:
        lines = file.readlines()
        
    in_jobs = False
    for line in lines:
        if line.startswith('jobs:'):
            in_jobs = True
            continue
        if in_jobs:
            jobs_content += line

merged_yaml = """name: Flowable Database Matrix Build

on:
  push:
    branches:
      - main
      - 'flowable-release-*'
    paths-ignore:
      - 'docs/**'
      - 'ide-settings/**'
      - '.github/ISSUE_TEMPLATE/**'
      - 'pull_request_template.md'
      - '.github/pull_request_template.md'
  pull_request:
    branches:
      - main
      - 'flowable-release-*'
    paths-ignore:
      - 'docs/**'
      - 'ide-settings/**'
      - '.github/ISSUE_TEMPLATE/**'
      - 'pull_request_template.md'
      - '.github/pull_request_template.md'

env:
  MAVEN_ARGS: >-
    -Dmaven.javadoc.skip=true
    -B -V --no-transfer-progress
    -Dhttp.keepAlive=false -Dmaven.wagon.http.pool=false -Dmaven.wagon.httpconnectionManager.ttlSeconds=120

jobs:
""" + jobs_content

with open('/app/flowable-engine/.github/workflows/database-matrix.yml', 'w') as out:
    out.write(merged_yaml)

for f in files:
    os.remove(os.path.join('/app/flowable-engine/.github/workflows', f))

print("Done")