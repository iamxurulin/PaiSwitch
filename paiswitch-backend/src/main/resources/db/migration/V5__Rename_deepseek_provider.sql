-- Keep provider display names version-agnostic; concrete model versions live in model_name.
UPDATE model_provider
SET name = 'DeepSeek'
WHERE code = 'deepseek'
  AND name = 'DeepSeek V3';
