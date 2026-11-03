export function appendDictatedPhrase(current: string, phrase: string): string {
  const joiner = !current || /\s$/.test(current) ? "" : " ";
  return `${current}${joiner}${phrase}`;
}

export function clampToColumn(value: string, maxLength?: number): string {
  return maxLength === undefined ? value : value.slice(0, maxLength);
}

export function columnFullSentence(maxLength: number): string {
  return `This box is full — it holds ${maxLength.toLocaleString("en-IN")} characters. Anything spoken or typed beyond that is not added.`;
}
