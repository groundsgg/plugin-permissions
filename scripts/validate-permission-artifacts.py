#!/usr/bin/env python3
"""Validate indexed permission metadata in final plugin and shaded host JARs."""
import argparse
import collections
import json
from pathlib import Path
import re
import zipfile

INDEX = 'META-INF/grounds/permissions.index'
PATH = re.compile(r'META-INF/grounds/permissions/([a-zA-Z0-9][a-zA-Z0-9._-]*)\.json')
SCOPES = {'GLOBAL', 'SERVER_TYPE', 'SERVER'}


def validate_artifact(path):
    records = {}
    with zipfile.ZipFile(path) as jar:
        counts = collections.Counter(jar.namelist())
        indexes = [entry for entry in jar.infolist() if entry.filename == INDEX]
        if not indexes:
            raise ValueError(f'Missing permission index in {path}')
        paths = set()
        for index in indexes:
            paths.update(line.strip() for line in jar.read(index).decode().splitlines()
                         if line.strip() and not line.strip().startswith('#'))
        if not paths:
            raise ValueError(f'Empty permission index in {path}')
        for resource in sorted(paths):
            match = PATH.fullmatch(resource)
            if not match or '..' in match[1]:
                raise ValueError(f'Unsafe resource path: {resource}')
            if counts[resource] == 0:
                raise ValueError(f'Missing indexed resource: {resource}')
            if counts[resource] != 1:
                raise ValueError(f'Duplicate resource path: {resource}')
            manifest = json.loads(jar.read(resource))
            source, version = manifest.get('source'), manifest.get('sourceVersion')
            if source != match[1]:
                raise ValueError(f'Manifest source/path mismatch: {resource}')
            if not isinstance(version, str) or not version.strip() or '${' in version or '@' in version:
                raise ValueError(f'Missing or unexpanded source version: {resource}')
            permissions = manifest.get('permissions')
            if not isinstance(permissions, list) or not permissions:
                raise ValueError(f'Empty permission entries: {resource}')
            keys = set()
            for entry in permissions:
                for field in ('key', 'label', 'description'):
                    if not isinstance(entry.get(field), str) or not entry[field].strip():
                        raise ValueError(f'Missing {field}: {resource}')
                if entry['key'] in keys:
                    raise ValueError(f'Duplicate permission key: {entry["key"]}')
                keys.add(entry['key'])
                scopes = entry.get('supportedScopes')
                if not isinstance(scopes, list) or not scopes or any(scope not in SCOPES for scope in scopes):
                    raise ValueError(f'Unsupported or empty scope: {resource}')
            records[source] = manifest
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('artifacts', type=Path, nargs='+')
    parser.add_argument('--require-source', action='append', default=[])
    args = parser.parse_args()
    for artifact in args.artifacts:
        try:
            records = validate_artifact(artifact)
            missing = set(args.require_source) - records.keys()
            if missing:
                raise ValueError(f'Missing expected sources: {sorted(missing)}')
        except (ValueError, OSError, zipfile.BadZipFile) as error:
            parser.exit(1, f'{artifact}: {error}\n')
        print(json.dumps({'artifact': str(artifact), 'sources': {
            source: {'version': record['sourceVersion'], 'entries': len(record['permissions'])}
            for source, record in records.items()}}, sort_keys=True))


if __name__ == '__main__':
    main()
