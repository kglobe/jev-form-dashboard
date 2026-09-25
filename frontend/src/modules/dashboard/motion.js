// spec 10.1、10.9：JS 要判斷使用者有沒有開「減少動態效果」都用這個
export const prefersReducedMotion = () => matchMedia('(prefers-reduced-motion: reduce)').matches
