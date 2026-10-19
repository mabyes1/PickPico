"""Regression checks for RELRO protection rounding, independent of Android tools."""

import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "alignment", Path(__file__).with_name("verify-native-alignment.py"))
alignment = importlib.util.module_from_spec(spec)
spec.loader.exec_module(alignment)


def writable(address, size):
    return (1, 6, 0, address, 0, size, size, 16384)


class RelroTest(unittest.TestCase):
    def test_tail_padding_is_safe_when_next_writable_segment_starts_on_next_page(self):
        self.assertFalse(alignment.relro_overlaps_writable_data(
            [writable(0x4000, 0x1000), writable(0x8000, 0x1000)], 0x4000, 0x1000))

    def test_tail_cannot_protect_next_writable_segment(self):
        self.assertTrue(alignment.relro_overlaps_writable_data(
            [writable(0x4000, 0x1000), writable(0x6000, 0x1000)], 0x4000, 0x1000))

    def test_tail_cannot_protect_data_in_same_segment(self):
        self.assertTrue(alignment.relro_overlaps_writable_data(
            [writable(0x4000, 0x2000)], 0x4000, 0x1000))

    def test_prefix_cannot_protect_data_before_relro(self):
        self.assertTrue(alignment.relro_overlaps_writable_data(
            [writable(0x4000, 0x4000)], 0x5000, 0x3000))


if __name__ == "__main__":
    unittest.main()
