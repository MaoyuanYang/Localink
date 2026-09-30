export function fenToYuan(fen: number): string {
  return (fen / 100).toFixed(2)
}

export function scoreOf(score: number): string {
  return (score / 10).toFixed(1)
}

export function firstImage(images: string): string | undefined {
  return images?.split(',').map((s) => s.trim()).filter(Boolean)[0]
}
