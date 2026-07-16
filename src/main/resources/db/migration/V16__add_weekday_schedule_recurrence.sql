-- 「每個上班日／工作日」和「每週同一天」不是同一種週期。
-- 既有版本只能存 WEEKLY，曾把明確命名為上班日的固定行程錯存成每週一次；
-- 依標題中已保存的明確語意修正這批資料，其他 WEEKLY 行程維持原狀。
UPDATE schedule_item
SET recurrence = 'WEEKDAYS'
WHERE recurrence = 'WEEKLY'
  AND (title LIKE '%上班日%' OR title LIKE '%工作日%');
