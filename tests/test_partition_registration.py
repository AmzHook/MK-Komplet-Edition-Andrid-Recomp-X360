import importlib.util
from pathlib import Path
import re
import unittest

spec = importlib.util.spec_from_file_location('partition_registration', Path(__file__).resolve().parents[1] / 'tools/partition_registration.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RegistrationPartitionTests(unittest.TestCase):
    def source(self, calls):
        return '#include "sample_init.h"\nvoid sample_RegisterFunctions(rex::runtime::IModuleRegistrar* registrar) {\n' + '\n'.join(calls) + '\n}\n'

    def test_large_registration_preserves_every_address_and_order(self):
        calls = [f'  registrar->SetFunction(0x{0x82000000 + number * 4:08X}, sub_{number});' for number in range(1201)]
        result, count, chunks = module.partition(self.source(calls))
        self.assertEqual((count, chunks), (1201, 3))
        self.assertEqual(re.findall(r'  registrar->SetFunction\([^\n]+', result), calls)
        bodies = re.findall(r'static.*?\{(.*?)\}', result, re.DOTALL)
        self.assertEqual([body.count('SetFunction') for body in bodies], [500, 500, 201])
        main = result[result.rfind('void sample_RegisterFunctions('):]
        self.assertLess(main.index('_batch_0(registrar)'), main.index('_batch_1(registrar)'))
        self.assertLess(main.index('_batch_1(registrar)'), main.index('_batch_2(registrar)'))

    def test_import_registration_and_prefix_are_preserved(self):
        result, _, _ = module.partition(self.source(['  registrar->SetFunction(0x83376854, __imp__XAudioGetSpeakerConfig);']))
        self.assertTrue(result.startswith('#include "sample_init.h"'))
        self.assertIn('__imp__XAudioGetSpeakerConfig', result)

    def test_conditional_or_other_side_effect_is_rejected(self):
        for body in ['if (enabled) registrar->SetFunction(0x82000000, sub_0);', '  registrar->SetFunction(0x82000000, sub_0);\n  do_other_work();']:
            with self.assertRaises(ValueError):
                module.partition(self.source([body]))

    def test_empty_or_different_signature_is_rejected(self):
        for source in [self.source([]), 'void other() {}']:
            with self.assertRaises(ValueError):
                module.partition(source)


if __name__ == '__main__':
    unittest.main()
