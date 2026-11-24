// The answer being written (port of the Vaadin CoachPresenter.AnswerDraft). The text is kept until the backend
// accepted it, so a failed send never loses it. The voice flag is true once any part came from a transcription, even if
// the user edited it afterwards; it resets when the field is emptied (a fresh answer).

export function createAnswerDraft() {
  let text = '';
  let voice = false;
  return {
    text: () => text,
    voice: () => voice,
    /** The user typed or edited the field. */
    edited(value) {
      text = value ?? '';
      if (text.trim() === '') {
        voice = false;
      }
    },
    /** A transcription arrives: it is added to what is already written, for review. Returns the new field value. */
    transcribed(transcript) {
      const add = (transcript ?? '').trim();
      if (add === '') {
        return text;
      }
      text = text.trim() === '' ? add : `${text.trimEnd()} ${add}`;
      voice = true;
      return text;
    },
    /** Null when there is nothing to send. */
    request() {
      return text.trim() === '' ? null : { text: text.trim(), voice };
    },
    sent() {
      text = '';
      voice = false;
    },
  };
}
