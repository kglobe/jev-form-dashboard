---
landmark: decision-log
---

# 決策紀錄

做完之後還要照著走的決定。被推翻的決定改 Status，不刪列。

| Date | Context | Decision | Why | Source | Status | Ref |
|---|---|---|---|---|---|---|
| 2026-10-23 | 本期重點要列哪些卡 | 只列異常檢查有標出來、嚴重程度是「值得注意」以上的卡，最多 3 張；都沒有時寫「本期沒有需要注意的變化（檢查了 N 張卡）」 | 本期重點跟卡片徽章不會互相矛盾 | 使用者 | active | `docs/superpowers/specs/2026-10-23-period-digest-design.md` 2.1、2.3 |
| 2026-10-23 | 本期重點每一筆寫什麼 | 嚴重程度、卡片標題、兩期和變化最大那條系列的數字，按了捲到那張卡 | 使用者選 | 使用者 | active | 同上 2.2 |
| 2026-10-23 | 本期重點怎麼排名 | 用異常檢查現有的嚴重程度答案，取整後的等級比，不多問 JEV | 題目不變，答案快取照樣命中，jev-1.13.0 的門檻不用重調 | Claude | active | 同上 1、2.1 |
| 2026-10-23 | `/highlight` 在 JEV 失敗時回什麼 | 回 204，前端當成沒有檢查結果；沒有卡能檢查時回 200 和空的 `checked` | 三種結果分得開；回 5xx 會讓沒設 API key 的環境每次都在 console 留錯誤 | Claude | active | 同上 3 |
| 2026-10-23 | 「變化最大」的系列怎麼挑 | 兩期相減的絕對值最大，跟送 JEV 時留 10 條系列的標準一樣 | 用百分比會讓基數很小的系列排到前面 | Claude | active | 同上 2.2 |
| 2026-10-23 | 驗證 skill 放哪裡 | `.claude/skills/verify-jev-dashboard/` 進版控，`.gitignore` 只放行 `.claude/skills/` | 改到使用者看得到的功能時，要在同一個 commit 更新 `features/` | Claude | active | `.claude/skills/verify-jev-dashboard/SKILL.md` |
