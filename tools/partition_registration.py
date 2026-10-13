#!/usr/bin/env python3
"""Split the generated startup registration function into bounded helpers.

Preserves every SetFunction statement and its order. Writes a build-only copy;
does not modify generated game functions or ReXGlue codegen output.
"""
import argparse
from pathlib import Path
import re


def partition(source, chunk_size=500):
    match = re.fullmatch(r'(.*?)void (\w+_RegisterFunctions)\(rex::runtime::IModuleRegistrar\* registrar\)\s*\{(.*?)\}\s*', source, re.DOTALL)
    if not match:
        raise ValueError('Unexpected generated registration function format.')
    prefix, name, body = match.groups()
    statement = r'  registrar->SetFunction\(0x[0-9A-Fa-f]+, [A-Za-z_][A-Za-z_0-9]*\);'
    calls = re.findall(statement, body)
    if not calls or re.sub(statement, '', body).strip():
        raise ValueError('Registration contains unexpected statements; preserving original source.')
    output = [prefix]
    helpers = []
    for number, start in enumerate(range(0, len(calls), chunk_size)):
        helper = name + '_batch_' + str(number)
        helpers.append(helper)
        output.append('static __attribute__((noinline)) void ' + helper + '(rex::runtime::IModuleRegistrar* registrar) {\n' + '\n'.join(calls[start:start + chunk_size]) + '\n}\n\n')
    output.append('void ' + name + '(rex::runtime::IModuleRegistrar* registrar) {\n' + ''.join('  ' + helper + '(registrar);\n' for helper in helpers) + '}\n')
    result = ''.join(output)
    if re.findall(statement, result) != calls:
        raise ValueError('Registration statement/order preservation failed.')
    return result, len(calls), len(helpers)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    try:
        result, count, helpers = partition(args.source.read_text())
        args.output.parent.mkdir(parents=True, exist_ok=True)
        if not args.output.exists() or args.output.read_text() != result:
            args.output.write_text(result)
        print(f'Preserved {count} registration calls in {helpers} bounded startup helpers.')
    except (OSError, ValueError) as error:
        parser.exit(1, str(error) + '\n')


if __name__ == '__main__':
    main()
