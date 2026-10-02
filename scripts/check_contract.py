"""Offline contract consistency checks; not a replacement for real API/E2E tests."""
import json
import re
from pathlib import Path
root=Path(__file__).resolve().parents[1]
api=json.loads((root/'docs/openapi.yaml').read_text(encoding='utf-8'))
schemas=api['components']['schemas']
def visit(value):
    if isinstance(value,dict):
        if '$ref' in value:
            assert value['$ref'].startswith('#/components/schemas/')
            assert value['$ref'].split('/')[-1] in schemas,value['$ref']
        for child in value.values():visit(child)
    elif isinstance(value,list):
        for child in value:visit(child)
visit(api)
paths=set()
for source in (root/'backend/src/main/java').rglob('*Controller.java'):
    content=source.read_text(encoding='utf-8')
    prefix=re.search(r'@RequestMapping\("([^"]+)"\)',content)
    prefix=prefix.group(1) if prefix else ''
    for method,path in re.findall(r'@(Get|Post|Put|Patch|Delete)Mapping\("([^"]+)"\)',content):
        path=(prefix+path).removeprefix('/api/v1')
        assert path in api['paths'] and method.lower() in api['paths'][path],(method,path)
        paths.add((method.lower(),path))
documented={(method,path) for path,operations in api['paths'].items() for method in operations}
assert paths==documented,documented-paths
sql=(root/'backend/src/main/resources/db/migration/V1__initial_schema.sql').read_text(encoding='utf-8')
assert len(re.findall(r'CREATE TABLE ',sql))==13
assert 'UNIQUE(order_id)' in sql and "WHERE status IN ('CREATING','PENDING')" in sql
assert 'nextval' not in api['paths']
print(f'Contract consistent: {len(paths)} operations, {len(schemas)} schemas, 13 tables; all schema references resolved.')
