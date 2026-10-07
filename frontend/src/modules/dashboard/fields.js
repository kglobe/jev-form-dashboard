// 資料頁的欄位表和製圖頁的欄位標籤共用：型別的圖示和中文名稱
export const FIELD_TYPES = {
  number: { icon: 'mdi-pound', text: '數字' },
  date: { icon: 'mdi-calendar-blank-outline', text: '日期' },
  string: { icon: 'mdi-format-letter-case', text: '文字' },
}

// 資料來源共享 spec 6.4：製圖頁、合併表單的來源選單裡，別人分享的名稱後面加「（來自 帳號）」
export const sourceTitle = (ds, username) => (ds.createdBy === username ? ds.name : `${ds.name}（來自 ${ds.createdBy}）`)
