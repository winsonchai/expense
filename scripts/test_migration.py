#!/usr/bin/env python3
"""Exercise the shipped upgrade SQL against real SQLite and exported Room schemas."""
import json
import sqlite3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / 'app/schemas/com.winsonchai.expense.data.ExpenseDatabase'

class MigrationTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        schema = json.loads((SCHEMAS / '1.json').read_text())['database']
        for entity in schema['entities']:
            self.db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        self.db.execute("INSERT INTO accounts VALUES ('card-1', '信用卡', 'CIMB', '日常卡', '1234', 1)")
        self.db.execute("INSERT INTO accounts VALUES ('initial-TNG eWallet', '电子钱包', 'TNG eWallet', '个人', '', 1)")
        fields = [x['columnName'] for x in schema['entities'][0]['fields']]
        values = dict(id='expense-1', status='SAVED', date='2026-11-21', amountMinor=1250, amountInput='12.50',
                      currency='MYR', category='餐饮', merchant='我的商家', method='信用卡', accountId='card-1',
                      note='我的备注', source='分享图片', receiptPath='/private/receipts/original.img', mimeType='image/*',
                      receiptHash='abc123', rawText='原始收据', warnings='交易日期不明确，请手动填写，勿用截图日期。', candidates='12.50', createdAt=1)
        self.db.execute(f"INSERT INTO expenses ({','.join(fields)}) VALUES ({','.join('?' for _ in fields)})", [values[k] for k in fields])
        for line in (ROOT / 'app/src/main/assets/migrations/1_2.sql').read_text().splitlines():
            if line.strip() and not line.strip().startswith('--'):
                self.db.execute(line)

    def tearDown(self):
        self.db.close()

    def test_records_accounts_and_receipts_are_preserved(self):
        result = self.db.execute('SELECT id, amountMinor, currency, accountId, receiptPath, merchant, note, rawText, receiptNo, transactionTime FROM expenses').fetchone()
        self.assertEqual(('expense-1', 1250, 'MYR', 'card-1', '/private/receipts/original.img', '我的商家', '我的备注', '原始收据', '', ''), result)
        self.assertEqual(('日常卡', '1234'), self.db.execute("SELECT nickname,lastFour FROM accounts WHERE id='card-1'").fetchone())

    def test_builtin_labels_and_warnings_become_english(self):
        self.assertEqual(('Food & drinks', 'Credit card', 'Shared image'), self.db.execute('SELECT category,method,source FROM expenses').fetchone())
        self.assertIn('transaction date', self.db.execute('SELECT warnings FROM expenses').fetchone()[0])
        self.assertEqual(('Personal', 'E-wallet'), self.db.execute("SELECT nickname,method FROM accounts WHERE id='initial-TNG eWallet'").fetchone())

    def test_schema_matches_room_version_two(self):
        schema = json.loads((SCHEMAS / '2.json').read_text())['database']
        for entity in schema['entities']:
            actual = {row[1]: row for row in self.db.execute('PRAGMA table_info(' + entity['tableName'] + ')')}
            self.assertEqual({x['columnName'] for x in entity['fields']}, set(actual))
            for field in entity['fields']:
                row = actual[field['columnName']]
                self.assertEqual(field['affinity'], row[2])
                self.assertEqual(field['notNull'], bool(row[3]))
                self.assertEqual(field.get('defaultValue'), row[4])

if __name__ == '__main__':
    unittest.main()
