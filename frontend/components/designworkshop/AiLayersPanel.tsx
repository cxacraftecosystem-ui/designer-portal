"use client";

/**
 * WHAT A MACHINE PRODUCED IN THIS WORKSHOP, WHAT IT WAS PRODUCED FROM, AND WHO PUT THEIR NAME TO IT.
 *
 * This is the person-facing half of the layering law (`docs/PLAN-AI-TIERS-AND-CUSTOM-SECTIONS.md` §3,
 * `backend/app/services/ai_layers.py`). The model, the service and the five routes landed first and
 * deliberately: provenance goes in "before there is a backlog of unattributed AI text". But rule 3 —
 * *"a layer is inert until a person accepts it, and acceptance is recorded with who and when"* — is a
 * door with no handle until somebody can turn it. Without this screen every layer stays inert for
 * ever, the annexure has nothing to print, and the whole provenance chain is a table nobody can act
 * on.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * FIVE THINGS THIS PANEL WILL NOT DO, each of them a defect it would otherwise ship.
 *
 * 1. **IT NEVER WRITES A MODEL'S WORDS INTO A FORM FIELD.** There is no "apply to the stage" button,
 *    no "copy into this entry", no prefill of any kind, and there must never be one. Plan §3: no
 *    AI-produced value may feed a field that is compared across surfaces or any derived or computed
 *    field, because the same audio through Tier 1 on a phone and Tier 3 in the cloud differs
 *    legitimately and for ever — a cross-surface parity test that ever compared them would be failing
 *    on the design rather than on a bug. The server makes this true by construction: every write it
 *    can express is a `LayerWritePlan`, and `DwStageEntry` is absent from `WRITABLE_TABLES`, so
 *    naming it raises. This side keeps it true by having no such control to press. **If somebody adds
 *    one, they are changing the plan, not adding a convenience.**
 * 2. **IT NEVER PRINTS "UNRECORDED" AS BLANK, AND NEVER GUESSES A PROVIDER.** `provider` and `modelId`
 *    are NOT NULL and legitimately hold that literal string, because `media_queue._transcript_write`
 *    has never stored which of the four providers in `services/ai.py` produced a transcript. That is
 *    the common case for every transcript this system has ever produced, not an edge case. It is
 *    rendered as the words "not recorded", in the same quiet type as a recorded value — not blank
 *    (which reads as "there is nothing here"), not red (which would tell a designer their archive is
 *    broken), and never replaced by a plausible provider name, which would be a fabricated provenance
 *    record in the one table built to prevent them.
 * 3. **IT NEVER OFFERS A BUTTON WHOSE ONLY OUTCOME IS A REFUSAL.** Three of these routes refuse on a
 *    condition this screen can already see, and a control offered into a certain refusal teaches
 *    designers that refusals are noise — after which the one that matters is clicked through too.
 *    - **Accept, for a layer already accepted** (409): a second acceptance would overwrite the first
 *      acceptor's name, the single fact about an accepted layer a reader of a submitted report may
 *      need to trace. Withdraw is offered instead.
 *    - **Accept, for a layer whose text is withheld** (403): `textWithheld` on the row and the
 *      refusal in `accept_ai_layer` are the same computation on the same server, so the outcome is
 *      settled before the press. A sentence naming who to ask stands where the button was.
 *    - **Decline, for a layer that live layers derive from** (409): `deletion_plan` refuses while
 *      anything stands on it, and the things standing on it are drawn inside it on this very page.
 *    In all three the refusal's own next move is stated in place of the control, which is the same
 *    information one press earlier.
 * 4. **IT NEVER SHOWS A STATUS CODE.** Every refusal these five routes raise is already a sentence
 *    naming the next move, and `aiLayerProblem` shows the server's own words. The 409 on delete says
 *    "N layer(s) were produced from this one (…) and would be left describing something no longer on
 *    screen. Delete those first" — which is the sentence a designer can act on, and rewording it here
 *    would give one rule two voices.
 * 5. **IT NEVER PUTS THE TIER BEHIND A HOVER.** Plan §2.1: without the tier on the page, a
 *    cloud-diarized interview and a device-guessed one look alike, and a fleet running different tiers
 *    produces two classes of record nobody can tell apart. Every row carries a tier chip that says
 *    what the tier MEANS — "In the cloud", "On the handset" — rather than a numeral, because
 *    `AiTier.number` exists "for prose only, never for a comparison": Tier 1 is the only tier that
 *    works with no signal and Tier 3 is the only one carrying the craft keyterm list, so higher is not
 *    better in either direction.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * THE CHAIN IS THE LAYOUT, NOT A DECORATION. A cleaned transcript is drawn INSIDE the raw transcript
 * it was derived from, and a summary inside that. A flat list sorted by date scatters one recording's
 * rungs among another's, and the person deciding whether to accept a summary then has to reconstruct
 * from a column of cuids what it was a summary OF. Where two models read the SAME recording, their
 * chains sit side by side under one heading — that pairing is the whole safeguard §2.1 asks for.
 *
 * REGISTERING IS OFFERED HERE TOO, AND WITH NO PROVENANCE BOXES. `POST .../ai-layers` accepts an
 * optional provider, model id, version and language "for the one caller who genuinely knows — an
 * operator backfilling a period when a single provider was configured". A designer standing in front
 * of a transcript is not that caller: they would be typing a guess into the column that exists to
 * make guessing impossible. So the form has no boxes, the row is registered with the provenance the
 * server records honestly as unrecorded, and the panel says so before the press rather than after it.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  AlertTriangle,
  Check,
  CheckCircle2,
  EyeOff,
  Info,
  Layers,
  Loader2,
  Plus,
  RefreshCw,
  Trash2,
  Undo2
} from "lucide-react";

import { Markdown } from "@/components/Markdown";
import { useAuth } from "@/components/AuthProvider";
import { useConfirm } from "@/components/dialogs/ConfirmDialog";
import { formatDateTime } from "@/lib/format";
import {
  MAX_AI_LAYER_NOTE_CHARS,
  acceptDesignWorkshopAiLayer,
  aiLayerProblem,
  decisionLabel,
  deleteDesignWorkshopAiLayer,
  flattenAiLayerNodes,
  groupAiLayers,
  layerKindLabel,
  layerKindNote,
  layerKindNoun,
  layerProvenance,
  listDesignWorkshopAiLayers,
  readAiPayload,
  registerDesignWorkshopAiLayer,
  tierLabel,
  tierSentence,
  unacceptDesignWorkshopAiLayer,
  type DwAiDecisionRecord,
  type DwAiLayer,
  type DwAiLayerList,
  type DwAiLayerNode,
  type DwAiPayloadView
} from "@/lib/aiLayers";
import { listDesignWorkshopTranscripts, type DwTranscriptItem, type DwTranscriptList } from "@/lib/designWorkshops";

/**
 * Every decision this SESSION recorded, keyed by layer.
 *
 * The accept and withdraw responses carry the layer's whole `DwAiLayerDecision` history, and showing
 * it is what stops acceptance being read as a checkbox — the server's own route docstring says so.
 * There is no GET for the history, though, so a layer nobody has touched on this visit has none to
 * show, and the panel states that rather than implying an empty history is an unblemished one.
 */
type DecisionLog = Record<string, DwAiDecisionRecord[]>;

export function AiLayersPanel({ workshopId }: { workshopId: string }) {
  const { user } = useAuth();
  const confirm = useConfirm();

  const [list, setList] = useState<DwAiLayerList | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [includeDeleted, setIncludeDeleted] = useState(false);
  /**
   * Whether the full text of every layer has been fetched.
   *
   * ⚠ ALL OR NOTHING, BECAUSE THE SERVER HAS NO SINGLE-LAYER READ. `includeText` is a flag on the
   * LIST, so asking for one layer's text asks for every layer's — and a workshop can hold twenty-five
   * interviews, which is megabytes on one bar of signal. That is exactly why the list withholds text
   * by default. So this is one deliberate press, said out loud beside the button, rather than a
   * per-row control that would look free and would not be.
   */
  const [withText, setWithText] = useState(false);
  const [busyLayerId, setBusyLayerId] = useState<string | null>(null);
  const [decisions, setDecisions] = useState<DecisionLog>({});
  /** The layer whose withdrawal note is being typed, and the note. See `WithdrawForm` for why. */
  const [withdrawing, setWithdrawing] = useState<string | null>(null);

  const [transcripts, setTranscripts] = useState<DwTranscriptList | null>(null);
  const [transcriptsFailed, setTranscriptsFailed] = useState(false);
  const [registering, setRegistering] = useState<string | null>(null);

  /**
   * Which read is the current one. The house convention for a list screen (§14.5 of the frontend
   * reference: a generation counter, a `cancelled` flag, or an `AbortSignal`, and they are not
   * interchangeable) — `listDesignWorkshopAiLayers` takes no signal, so what matters is ignoring the
   * late answer rather than stopping it.
   *
   * IT IS NOT DECORATION HERE, because three different things start a read and two of them are
   * one-key presses a designer will drum on: the "Show declined layers" tick, the "Show the full
   * text" toggle — which asks for a payload measured in megabytes and therefore takes the longest to
   * come back — and every action's own refresh. Without this, pressing "Show the full text" and then
   * "Hide the full text" left the screen showing full transcripts under a button offering to show
   * them, because the first, slower answer landed last and won; the same race silently un-ticks
   * "Show declined layers" and, after an accept, can restore the pre-acceptance list over the
   * post-acceptance one — a screen showing a layer somebody has just signed as unaccepted, and
   * offering the Accept button again for a row the server would now refuse.
   */
  const loadSeq = useRef(0);

  const load = useCallback(
    async (options: { includeDeleted: boolean; withText: boolean }) => {
      const seq = ++loadSeq.current;
      setLoading(true);
      try {
        const next = await listDesignWorkshopAiLayers(workshopId, {
          includeDeleted: options.includeDeleted,
          includeText: options.withText
        });
        if (seq !== loadSeq.current) return;
        setList(next);
        setError(null);
      } catch (err) {
        if (seq !== loadSeq.current) return;
        setError(
          aiLayerProblem(
            err,
            // The disjunction, unresolved on purpose, exactly as the app's 404 page leaves it. A GET
            // that 404s here is either a server older than these routes or a workshop this account
            // cannot open, and naming only the first would assert that the workshop exists.
            "This workshop's AI layers could not be read. This server may not offer them yet, or this " +
              "may not be a workshop this account can open."
          )
        );
      } finally {
        // Only the newest read may put the screen back to rest; an overtaken one clearing this would
        // stop the spinner while its replacement is still on the wire.
        if (seq === loadSeq.current) setLoading(false);
      }
    },
    [workshopId]
  );

  useEffect(() => {
    void load({ includeDeleted, withText });
  }, [load, includeDeleted, withText]);

  /**
   * The recordings, so a chain can be headed by the field a designer recorded rather than by a cuid.
   *
   * The SAME endpoint the report's transcript annexure panel reads, joined on `mediaId`. A layer's
   * source is a bare media id and nothing else — naming it "Stage 7 · Interview with the master
   * weaver" is the difference between a heading and a hash. A failure here is not fatal: the chains
   * still render under their media ids and the panel says the names could not be fetched.
   */
  useEffect(() => {
    let cancelled = false;
    void listDesignWorkshopTranscripts(workshopId)
      .then((next) => {
        if (!cancelled) setTranscripts(next);
      })
      .catch(() => {
        if (!cancelled) setTranscriptsFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [workshopId]);

  const grouping = useMemo(() => groupAiLayers(list?.items ?? []), [list]);

  const recordingsById = useMemo(() => {
    const byId = new Map<string, DwTranscriptItem>();
    for (const item of transcripts?.items ?? []) byId.set(item.mediaId, item);
    return byId;
  }, [transcripts]);

  /** Which tiers are actually on this screen — the legend explains those and no others. */
  const tiersPresent = useMemo(() => {
    const seen: string[] = [];
    for (const layer of list?.items ?? []) if (!seen.includes(layer.tier)) seen.push(layer.tier);
    return seen;
  }, [list]);

  /** Recordings whose transcript is not yet a layer. The only way rows come into existence today. */
  const registerable = useMemo(() => {
    const already = new Set(
      (list?.items ?? [])
        .filter((layer) => layer.source?.kind === "MEDIA" && !layer.deletedAt)
        .map((layer) => layer.source?.id ?? "")
    );
    return (transcripts?.items ?? []).filter((item) => !already.has(item.mediaId));
  }, [list, transcripts]);

  const refresh = useCallback(() => load({ includeDeleted, withText }), [load, includeDeleted, withText]);

  /**
   * Is ANY write in flight — on this row or on another one?
   *
   * EVERY ACTION ON THE PAGE IS DISABLED WHILE ONE RUNS, not merely the row that owns it, and the
   * reason is that `busyLayerId` holds ONE id. Accept layer A, then press "Decline" on layer B while
   * A is still on the wire: B's press overwrote the id, A's `finally` then cleared it, and B's own
   * buttons came back to life with B's DELETE still unanswered. A second press then reached a row the
   * server had already declined and came back as "That layer is not in this workshop's list any
   * more — it may have been declined by somebody else while this screen was open", which is a true
   * sentence about a false situation: it was declined by this person, two seconds ago, from this
   * screen. The two refreshes also raced, and each handler's error and notice overwrote the other's.
   *
   * One at a time is the honest shape for a page whose acts are signatures, and it costs nothing: a
   * designer accepting a transcript is reading it first.
   */
  const anyBusy = busyLayerId !== null || registering !== null;

  async function accept(layer: DwAiLayer) {
    /*
      THE CONFIRM IS THE FEATURE, NOT A SAFETY RAIL AROUND IT.

      Accepting is the one act in this whole feature that attaches a PERSON to model output. Rule 4
      says the report prints the accepted layer and names it as such, so what is being agreed to is
      that this text — which a model wrote, possibly in a language the artisan did not speak — may
      appear in a document handed to a ministry officer with a name beside it. That has to be said in
      words before the press, in the same register as the identity-card reader's confirm panel, which
      spells out that a misread digit belongs to nobody and therefore passes every check the system
      has. A dialog reading "Accept this layer?" would be a shrug.

      Warning tone rather than danger: this is consequential and recoverable — the withdrawal exists —
      whereas danger tone puts focus on Cancel and refuses a backdrop dismiss, which is the treatment
      for something that destroys work.
    */
    const provenance = layerProvenance(layer);
    const readable = withText && typeof layer.text === "string" && layer.text.trim().length > 0;
    const agreed = await confirm({
      title: `Accept this ${layerKindNoun(layer.kind)} in your name?`,
      tone: "warning",
      confirmLabel: "I have read it — accept it in my name",
      cancelLabel: "Not yet",
      body: (
        <>
          Accepting records <strong>you</strong>, by name and at this moment, as the person who read this text and stands
          behind it. Accepted layers are the ones a report is allowed to print, and it prints them named as AI-produced —
          so these words can reach a document handed to a ministry officer with your name attached to them.{" "}
          {provenance.unrecorded
            ? "Nothing was recorded about what produced this text: no provider and no model. If it turns out to be wrong in six months, there is nothing to trace it back to."
            : `It was produced by ${provenance.provider}, model ${provenance.model}, ${tierLabel(layer.tier).toLowerCase()}.`}
        </>
      ),
      note: (
        <>
          {readable
            ? null
            : "The full text is not on screen. Press “Show the full text” and read it before you accept — an acceptance says you did. "}
          Nobody else can accept it after you: the server refuses a second acceptance because it would overwrite your name.
          You can withdraw yours later and the withdrawal is recorded too, but a report generated in the meantime will still
          say you accepted it.
        </>
      )
    });
    if (!agreed) return;

    setBusyLayerId(layer.id);
    setError(null);
    setNotice(null);
    try {
      const result = await acceptDesignWorkshopAiLayer(workshopId, layer.id);
      setDecisions((current) => ({ ...current, [layer.id]: result.decisions }));
      setNotice(`Accepted. The ${layerKindNoun(layer.kind)} now carries your name and the moment you accepted it.`);
      await refresh();
    } catch (err) {
      setError(aiLayerProblem(err, "That layer could not be accepted."));
    } finally {
      setBusyLayerId(null);
    }
  }

  async function withdraw(layer: DwAiLayer, note: string) {
    setBusyLayerId(layer.id);
    setError(null);
    setNotice(null);
    try {
      const result = await unacceptDesignWorkshopAiLayer(workshopId, layer.id, note);
      setDecisions((current) => ({ ...current, [layer.id]: result.decisions }));
      setWithdrawing(null);
      setNotice(
        "Your acceptance has been withdrawn and the reason kept. The layer itself is untouched and can be accepted again. " +
          "Any report already generated still names it as accepted, because that document does not change."
      );
      await refresh();
    } catch (err) {
      setError(aiLayerProblem(err, "That acceptance could not be withdrawn."));
    } finally {
      setBusyLayerId(null);
    }
  }

  async function decline(layer: DwAiLayer) {
    const agreed = await confirm({
      title: `Decline this ${layerKindNoun(layer.kind)}?`,
      tone: "danger",
      confirmLabel: "Decline it",
      body: (
        <>
          It stops being offered and nothing can print it. The row is kept rather than erased, so “a model proposed this and
          a person said no” stays answerable — which is the only place that fact is recorded at all.
        </>
      ),
      note: (
        <>
          The recording it was made from is not touched, and neither is the layer above it in the chain. If something was
          produced FROM this one, the server will refuse until that has been declined first.
        </>
      )
    });
    if (!agreed) return;

    setBusyLayerId(layer.id);
    setError(null);
    setNotice(null);
    try {
      await deleteDesignWorkshopAiLayer(workshopId, layer.id);
      setNotice("Declined. It is kept as the record that a person said no, and can be registered again if the material is still wanted.");
      await refresh();
    } catch (err) {
      setError(aiLayerProblem(err, "That layer could not be declined."));
    } finally {
      setBusyLayerId(null);
    }
  }

  async function register(item: DwTranscriptItem) {
    setRegistering(item.mediaId);
    setError(null);
    setNotice(null);
    try {
      /*
        NO PROVENANCE IS SENT, AND THAT IS THE HONEST ANSWER RATHER THAN THE LAZY ONE. The body accepts
        five optional fields — `provider`, `modelId`, `modelVersion`, `language` and `producedAt` — and
        the route substitutes the literal UNRECORDED for the first two when they are absent, leaving the
        other three null. Nothing on this device knows which of the four providers in `services/ai.py`
        transcribed this clip — the queue never stored it — so any value typed here would be a guess
        written into the column that exists to make guessing impossible.
      */
      const result = await registerDesignWorkshopAiLayer(workshopId, { sourceMediaId: item.mediaId });
      setNotice(
        result.total > 1
          ? `Registered as ${result.total} layers: the provider's own words, and the rewritten version derived from them. ` +
              "Both are inert until somebody accepts them."
          : "Registered as one layer. It is inert until somebody accepts it."
      );
      await refresh();
    } catch (err) {
      setError(aiLayerProblem(err, "That recording could not be registered as a layer."));
    } finally {
      setRegistering(null);
    }
  }

  const total = list?.total ?? 0;
  const accepted = list?.accepted ?? 0;

  return (
    <section className="grid gap-5">
      <div className="panel grid gap-3 p-4">
        <div className="flex items-start gap-3">
          <span className="mt-0.5 grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-field-200 text-field-600">
            <Layers className="h-4 w-4" aria-hidden />
          </span>
          <div className="min-w-0">
            <h2 className="font-display text-base font-bold text-ink-900">What a model produced, and who accepted it</h2>
            <p className="mt-1 text-sm leading-6 text-ink-muted">
              Every row here is something a machine wrote from this workshop&apos;s own material — never an edit of it. Each
              one says which machine ran the model, which model it was, and what it was produced from, so a systematic error
              found in six months can be traced back to the material it damaged. <strong>Nothing is printed until a person
              accepts it</strong>, and an acceptance records who and when.
            </p>
            <p className="mt-2 text-sm leading-6 text-ink-muted">
              These layers are annexure material and suggestions. None of them feeds a stage field: the same recording
              through a model on the handset and a model in the cloud produces different text, legitimately and for ever, so
              an AI value in a field the handset and the server compare would break that agreement by design rather than by
              accident.
            </p>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-x-4 gap-y-2 border-t border-line-200 pt-3 text-sm">
          <span className="text-ink-700">
            <strong className="text-ink-900">{total}</strong> layer{total === 1 ? "" : "s"}
            {includeDeleted ? " (declined ones included)" : ""}
          </span>
          <span className="text-ink-700">
            <strong className="text-ink-900">{accepted}</strong> accepted
          </span>
          <label className="flex items-center gap-2 text-ink-700">
            <input
              type="checkbox"
              className="h-3.5 w-3.5 accent-purple-700"
              checked={includeDeleted}
              onChange={(event) => setIncludeDeleted(event.currentTarget.checked)}
              data-testid="ai-layers-include-deleted"
            />
            Show declined layers
          </label>
          <button type="button" className="field-button-secondary" onClick={() => setWithText((current) => !current)}>
            {withText ? "Hide the full text" : "Show the full text"}
          </button>
          <button type="button" className="field-button-secondary" onClick={() => void refresh()} disabled={loading}>
            <RefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} aria-hidden />
            {loading ? "Reading…" : "Reload"}
          </button>
        </div>

        {!withText ? (
          <p className="text-xs leading-5 text-ink-500">
            This server has no way to read one layer&apos;s text on its own, so “Show the full text” fetches the text of
            every layer on this screen in a single request. A workshop can hold twenty-five interviews, which is why the
            list does not carry it by default.
          </p>
        ) : null}

        {/* WHERE AN ACCEPTANCE ACTUALLY LANDS, READ OFF THE SERVER RATHER THAN REMEMBERED — and the
            correction of a sentence that stood here and was false in the dangerous direction. An
            earlier draft of this panel told the person signing that "the report annexure that prints
            accepted layers has not been built yet", so accepting was a note kept for later. It is
            built: `services/report_ai_layers.append_ai_layer_annexure` renders the index, the
            provenance line and the text, `report_builder` calls it for the ANNEXURE_AI_LAYERS
            section (report_builder.py:1986), `design_workshops.attach_report_ai_layers` loads it
            through `ai_layers.accepted_layers`, and `apply_report_settings` splices the section into
            any template when a generate request carries `includeAiLayers`.

            AND THE SWITCH HAS SINCE LANDED TOO. This note went on to say "what is genuinely missing
            is the switch: `grep includeAiLayers` finds nothing in `frontend/` and nothing in
            `android/`" — true when written, false within the hour, because the report screen gained
            an "Include machine-assisted text" checkbox that sends the flag. That is TWICE this one
            paragraph has had to be repaired, both times for the same reason: it describes what the
            REST of the system does, from a `grep` taken at one moment, in a tree several lanes were
            editing at once.

            SO IT NO LONGER DESCRIBES THE PIPELINE AT ALL. What a person signing needs to know is
            what their signature does, and that has been true throughout and is not going to change:
            accepting is what makes a layer printable, and a report can carry it. Whether some other
            screen has a tick box today is not their business and is exactly the kind of claim that
            goes stale behind their back — on the one screen where a stale reassurance is how model
            prose ends up in a government document under a name nobody meant to lend it.

            The Android app still cannot ask for the annexure, and that is deliberately NOT stated
            here either: it is a fact about a different application, it would be read as a caveat on
            this signature, and it is recorded where it belongs — `ReportSettings.UNSUPPORTED_SECTIONS`
            on the handset, which says it to the person actually generating the file there. */}
        <p className="flex items-start gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
          <Info className="mt-1 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <span>
            Accepting is what makes a layer printable. A report asked to include machine-assisted text carries every
            layer accepted here, names each one as machine-assisted, and prints it with the model that produced it and
            the person who accepted it. Treat an acceptance as a signature on text that is ready to go into a document,
            not as a note kept for later.
          </span>
        </p>

        {/* BOTH ARE LIVE REGIONS, because both appear in response to a press and neither is anywhere
            near where the reader is looking: the buttons that produce them sit rows further down the
            page, inside a chain, and after an accept the focused element is a button that has just
            been replaced by a different one. A sighted designer sees the banner change colour at the
            top of the panel; without these, a screen-reader user pressed "Accept this layer", heard
            nothing at all, and had no way to tell an acceptance that was refused from one that
            landed. `role="alert"` (assertive) for the refusal and `role="status"` (polite) for the
            confirmation is the split every other panel in this repository uses — FieldInput.tsx:213
            and :1454, WorkshopCodeScanner.tsx:486 — and it is the right way round: a refusal is
            something to act on now, a success may wait for a pause in the reading. */}
        {error ? (
          <p role="alert" className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm leading-6 text-red-700">
            {error}
          </p>
        ) : null}

        {notice ? (
          <p
            role="status"
            className="flex items-start gap-2 rounded-md border border-success-600/25 bg-success-100 px-3 py-2 text-sm leading-6 text-success-600"
          >
            <CheckCircle2 className="mt-1 h-4 w-4 shrink-0" aria-hidden />
            <span>{notice}</span>
          </p>
        ) : null}
      </div>

      {loading && !list ? <p className="text-sm text-ink-700">Reading this workshop&apos;s layers…</p> : null}

      {list && !grouping.recordings.length && !grouping.unrooted.length ? (
        <div className="panel grid gap-2 p-4">
          <h3 className="font-display text-sm font-bold text-ink-900">Nothing has been registered yet</h3>
          <p className="text-sm leading-6 text-ink-muted">
            {includeDeleted
              ? "No model output has been recorded against this workshop, declined or otherwise."
              : "No model output has been recorded against this workshop. If something was registered and then declined, tick “Show declined layers” — a declined layer is kept as the record that a person said no."}
          </p>
        </div>
      ) : null}

      {grouping.recordings.map((group) => {
        const recording = recordingsById.get(group.mediaId);
        const layersHere = flattenAiLayerNodes(group.roots).length;
        return (
          <div key={group.mediaId} className="panel grid gap-3 p-4">
            <div>
              <h3 className="font-display text-sm font-bold text-ink-900">
                {recording ? `${recording.stageNumber}. ${recording.label}` : "A recording or photograph of this workshop"}
              </h3>
              <p className="mt-1 text-xs leading-5 text-ink-500">
                {recording
                  ? `${recording.stageTitle} · ${recording.fieldLabel} · ${recording.durationText}`
                  : transcriptsFailed
                    ? "The recordings could not be listed, so this one cannot be named here. The chain below is unaffected."
                    : // A photograph's OCR chain roots on an IMAGE, which the transcripts endpoint does
                      // not list at all — so "not in that list" is an ordinary answer, not a fault.
                      "Not in this workshop's transcript list — it may be a photograph, or a recording this account cannot read."}
                {" · "}
                <span className="font-mono">{group.mediaId}</span>
              </p>
              <p className="mt-1 text-xs leading-5 text-ink-500">
                {layersHere} layer{layersHere === 1 ? "" : "s"} stand
                {layersHere === 1 ? "s" : ""} on it
                {group.roots.length > 1
                  ? ` in ${group.roots.length} separate chains — more than one model read the same material, and the tiers below say which is which.`
                  : "."}
              </p>
            </div>

            <ul className="grid gap-3">
              {group.roots.map((node) => (
                <LayerRow
                  key={node.layer.id}
                  node={node}
                  parentKind={null}
                  withText={withText}
                  userId={user?.id ?? null}
                  busyLayerId={busyLayerId}
                  anyBusy={anyBusy}
                  decisions={decisions}
                  withdrawing={withdrawing}
                  onAccept={accept}
                  onWithdrawOpen={setWithdrawing}
                  onWithdraw={withdraw}
                  onDecline={decline}
                />
              ))}
            </ul>
          </div>
        );
      })}

      {grouping.unrooted.length ? (
        <div className="panel grid gap-3 p-4">
          <div>
            <h3 className="font-display text-sm font-bold text-ink-900">
              {grouping.unrooted.length} layer{grouping.unrooted.length === 1 ? "" : "s"} could not be attached to a
              recording
            </h3>
            {/* SHOWN, NEVER DROPPED. A list that quietly stops is indistinguishable from a workshop
                with nothing in it, and that confusion is the most repeated defect class in this
                repository. Each reason gets its own sentence because each has a different next move. */}
            <p className="mt-1 text-sm leading-6 text-ink-muted">
              They are listed anyway rather than hidden, because a layer that vanished from this screen would look exactly
              like a layer that was never made.
            </p>
          </div>
          <ul className="grid gap-3">
            {grouping.unrooted.map((entry) => (
              <li key={entry.node.layer.id} className="grid gap-2">
                <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
                  {entry.reason === "NO_SOURCE"
                    ? "This row does not say what it was made from, which is a shape the database itself refuses — it can only have arrived around the API. It cannot be printed or trusted until the row is repaired."
                    : entry.reason === "SOURCE_NOT_LISTED"
                      ? "The layer this was made from is not in this list. Tick “Show declined layers” above — it may have been declined."
                      : entry.reason === "UNKNOWN_SOURCE"
                        ? // NOT A FAULT IN THE ROW, unlike the other three, and it must not be worded
                          // as one: this build simply does not know the kind of thing the server says
                          // this layer was made from. The honest answer is to say so and to name the
                          // remedy, which is an update to this app rather than a repair to the data.
                          `This layer says it was made from a “${entry.node.layer.source?.kind ?? ""}”, which this screen does not know about. Its provenance below is complete; only its place in the chain cannot be drawn. This app is probably older than the server it is talking to.`
                        : "This layer's chain leads back to itself, so the recording underneath it cannot be found. It cannot be printed until the row is repaired."}
                </p>
                <ul className="grid gap-3">
                  <LayerRow
                    node={entry.node}
                    parentKind={null}
                    withText={withText}
                    userId={user?.id ?? null}
                    busyLayerId={busyLayerId}
                    anyBusy={anyBusy}
                    decisions={decisions}
                    withdrawing={withdrawing}
                    onAccept={accept}
                    onWithdrawOpen={setWithdrawing}
                    onWithdraw={withdraw}
                    onDecline={decline}
                  />
                </ul>
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {tiersPresent.length ? (
        <div className="panel grid gap-2 p-4">
          <h3 className="font-display text-sm font-bold text-ink-900">What the tiers on this screen mean</h3>
          <p className="text-sm leading-6 text-ink-muted">
            Not a ranking, in either direction. Only the handset works with no signal, and only the cloud carries the craft
            vocabulary that stops <em>dabu</em> being written as <em>double</em>.
          </p>
          <ul className="grid gap-2">
            {tiersPresent.map((tier) => (
              <li key={tier} className="text-sm leading-6 text-ink-700">
                <span className="font-medium text-ink-900">{tierLabel(tier)}</span> — {tierSentence(tier)}
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      <RegisterPanel
        items={registerable}
        failed={transcriptsFailed}
        loaded={transcripts !== null}
        // "Which recordings have no layer yet" is a question about BOTH lists, and `registerable`
        // answers it by subtracting one from the other. While the layers are unread — the first
        // moment of the screen, and for ever if that read failed — the subtraction has nothing to
        // subtract, so every recording looks unregistered and every button is a 409 waiting to
        // happen ("This recording is already registered as a Tier 3 raw transcript by the same
        // model"). Offering them anyway would be inviting the refusal, so the panel says which list
        // it is missing instead.
        layersKnown={list !== null}
        busyMediaId={registering}
        anyBusy={anyBusy}
        onRegister={register}
      />
    </section>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * One layer, and everything derived from it drawn inside it
 * ──────────────────────────────────────────────────────────────────────────── */

function LayerRow({
  node,
  parentKind,
  withText,
  userId,
  busyLayerId,
  anyBusy,
  decisions,
  withdrawing,
  onAccept,
  onWithdrawOpen,
  onWithdraw,
  onDecline
}: {
  node: DwAiLayerNode;
  /** The kind of the layer this one was produced from — null when it stands on the recording. */
  parentKind: string | null;
  withText: boolean;
  userId: string | null;
  busyLayerId: string | null;
  /** Some write, anywhere on the page, is on the wire. See `anyBusy` in the panel for why it is global. */
  anyBusy: boolean;
  decisions: DecisionLog;
  withdrawing: string | null;
  onAccept: (layer: DwAiLayer) => void;
  onWithdrawOpen: (layerId: string | null) => void;
  onWithdraw: (layer: DwAiLayer, note: string) => void;
  onDecline: (layer: DwAiLayer) => void;
}) {
  const { layer } = node;
  const provenance = layerProvenance(layer);
  const note = layerKindNote(layer.kind);
  const payload = readAiPayload(layer.payload);
  const busy = busyLayerId === layer.id;
  const declined = layer.deletedAt !== null;
  const history = decisions[layer.id] ?? [];

  /**
   * The layers still standing on this one — exactly what the DELETE would be refused for.
   *
   * `ai_layers.deletion_plan` is given `derived_from(layer_id)`, which is every layer whose
   * `sourceLayerId` is this one and whose `deletedAt` is null, and it refuses with a 409 naming their
   * kinds. Those rows are the ones drawn INSIDE this row, so the panel can see the refusal coming
   * before the press, and the whole point of drawing the chain is that they are already on screen.
   * Declined children are filtered out because the server ignores them too — a chain whose upper rung
   * has been declined can have its lower rung declined afterwards, which is the ordinary way a
   * two-rung registration is undone.
   */
  const liveChildren = node.children.filter((child) => child.layer.deletedAt === null);

  return (
    <li>
      <div
        className={`grid gap-2 rounded-md border px-3 py-3 ${
          declined
            ? "border-line-200 bg-surface-50"
            : layer.accepted
              ? "border-purple-300 bg-card"
              : "border-line-200 bg-card"
        }`}
        data-testid="ai-layer-row"
      >
        <div className="flex flex-wrap items-start justify-between gap-x-3 gap-y-2">
          <div className="min-w-0">
            <p className="text-sm font-medium text-ink-900">{layerKindLabel(layer.kind)}</p>
            <p className="mt-0.5 text-xs leading-5 text-ink-500">
              {/* `layerKindNoun`, not `layerKindLabel`: the label degrades to a whole sentence for a
                  kind this build does not know, and interpolating that produced "Produced from the a
                  layer kind this screen does not know (DIARIZATION) above it." */}
              {parentKind
                ? `Produced from the ${layerKindNoun(parentKind)} above it.`
                : "Produced from the recording or photograph itself."}
              {note ? ` ${note}` : " This screen does not know this kind, so it cannot say what the row contains."}
            </p>
          </div>
          <div className="flex shrink-0 flex-wrap items-center gap-1.5">
            {/* THE TIER, ALWAYS, IN WORDS. Never a numeral and never behind a hover — see the file
                header. Purple rather than a status tint: it is a fact about the row, not a verdict. */}
            <span className="rounded-full border border-purple-300 bg-purple-50 px-2 py-0.5 text-xs font-medium text-purple-800">
              {tierLabel(layer.tier)}
            </span>
            {declined ? (
              <span className="inline-flex items-center gap-1 rounded-full border border-line-200 bg-surface-50 px-2 py-0.5 text-xs font-medium text-ink-500">
                <Trash2 className="h-3 w-3" aria-hidden />
                Declined {formatDateTime(layer.deletedAt)}
              </span>
            ) : layer.accepted ? (
              <span className="inline-flex items-center gap-1 rounded-full border border-success-600/25 bg-success-100 px-2 py-0.5 text-xs font-medium text-success-600">
                <Check className="h-3 w-3" aria-hidden />
                Accepted
              </span>
            ) : (
              <span className="rounded-full border border-line-200 bg-surface-50 px-2 py-0.5 text-xs font-medium text-ink-500">
                Not accepted
              </span>
            )}
          </div>
        </div>

        {/* PROVENANCE, INCLUDING THE PARTS NOBODY WROTE DOWN. `unrecorded` is drawn in the ordinary
            muted type rather than as a warning: it is the common case for every transcript this
            system has produced, and an alarm colour would say the archive is broken. */}
        <dl className="grid gap-x-4 gap-y-1 text-xs leading-5 text-ink-500 sm:grid-cols-2">
          <div className="flex gap-1">
            <dt className="font-medium text-ink-700">Produced by</dt>
            <dd>
              {provenance.provider}, model {provenance.model}
            </dd>
          </div>
          <div className="flex gap-1">
            <dt className="font-medium text-ink-700">Language</dt>
            <dd>{provenance.language}</dd>
          </div>
          <div className="flex gap-1">
            <dt className="font-medium text-ink-700">Model ran</dt>
            {/* NOT defaulted to the row's own creation time on either end: a transcript produced last
                March and registered today would otherwise carry today's date as a statement about
                when the model ran, which is a fabricated fact of exactly the kind the provenance
                columns exist to prevent. */}
            <dd>{layer.producedAt ? formatDateTime(layer.producedAt) : "not recorded"}</dd>
          </div>
          <div className="flex gap-1">
            <dt className="font-medium text-ink-700">Recorded here</dt>
            <dd>
              {formatDateTime(layer.createdAt)}
              {layer.createdById ? (layer.createdById === userId ? " by you" : " by another account") : ""}
            </dd>
          </div>
        </dl>

        {provenance.unrecorded ? (
          <p className="text-xs leading-5 text-ink-500">
            Nothing was stored about which provider or model produced this, so it is stated as not recorded rather than
            guessed at. A systematic model error found later cannot be traced back through this row.
          </p>
        ) : null}

        {layer.accepted ? (
          <p className="text-xs leading-5 text-ink-700">
            Accepted {formatDateTime(layer.acceptedAt)}{" "}
            {layer.acceptedById
              ? layer.acceptedById === userId
                ? "by you."
                : // A user id and not a name: this endpoint resolves no accounts, and inventing a name
                  // from a roster this account may not be allowed to read would be worse than an id
                  // that can at least be quoted to an administrator.
                  "by another account."
              : "— but no account is recorded against it, which should not be possible."}
            {layer.acceptedById && layer.acceptedById !== userId ? (
              <span className="ml-1 font-mono text-ink-500">{layer.acceptedById}</span>
            ) : null}
          </p>
        ) : null}

        {layer.textWithheld ? (
          <p className="flex items-start gap-2 rounded-md border border-line-200 bg-surface-50 px-2 py-1.5 text-xs leading-5 text-ink-700">
            <EyeOff className="mt-0.5 h-3.5 w-3.5 shrink-0 text-ink-500" aria-hidden />
            <span>
              The recording at the foot of this chain is not one this account may read, so the text, the preview and the
              structured payload are withheld — being able to open the workshop and being allowed to read a recording are
              two different permissions here. The provenance above is not the recording&apos;s content and is shown in full.
            </span>
          </p>
        ) : withText && typeof layer.text === "string" && layer.text.trim() ? (
          // Markdown, not a <pre>: transcripts carry bold speaker labels and `---` rules, and raw HTML
          // stays escaped because this renderer deliberately has no rehype-raw.
          <div className="max-h-80 overflow-y-auto rounded-md border border-line-200 bg-surface-50 px-3 py-2">
            <Markdown text={layer.text} />
          </div>
        ) : layer.preview ? (
          <p className="line-clamp-2 text-xs leading-5 text-ink-500">{layer.preview}</p>
        ) : layer.textChars === 0 ? (
          <p className="text-xs leading-5 text-ink-500">This layer carries no text; its content is the structure below.</p>
        ) : null}

        {!layer.textWithheld && layer.textChars ? (
          <p className="text-xs leading-5 text-ink-500">
            {layer.textChars.toLocaleString("en-IN")} characters
            {withText ? "" : " — press “Show the full text” above to read it"}
          </p>
        ) : null}

        {payload ? <PayloadView view={payload} /> : null}

        {history.length ? (
          <div className="rounded-md border border-line-200 bg-surface-50 px-2 py-1.5">
            <p className="text-xs font-medium text-ink-700">Decisions recorded in this session</p>
            <ul className="mt-1 grid gap-0.5">
              {history.map((entry) => (
                <li key={entry.id} className="text-xs leading-5 text-ink-500">
                  {decisionLabel(entry.decision)} · {formatDateTime(entry.createdAt)}
                  {entry.actorId === userId ? " · by you" : " · by another account"}
                  {entry.note ? ` · “${entry.note}”` : ""}
                </li>
              ))}
            </ul>
            {/* The log is authoritative and complete on the server; this screen only ever sees the
                slice an action on this visit returned, and saying so stops a short list being read as
                a layer with a short history. */}
            <p className="mt-1 text-xs leading-5 text-ink-500">
              Earlier decisions are kept by the server but cannot be read from this screen — there is no endpoint for the
              history on its own.
            </p>
          </div>
        ) : null}

        {declined ? (
          <p className="text-xs leading-5 text-ink-500">
            Declined layers cannot be accepted, withdrawn or declined again. Register the material afresh if it is still
            wanted; this row stays as the record that somebody said no.
          </p>
        ) : withdrawing === layer.id ? (
          <WithdrawForm busy={busy} onCancel={() => onWithdrawOpen(null)} onSubmit={(text) => onWithdraw(layer, text)} />
        ) : (
          <div className="grid gap-2">
            {/* A row with no buttons left in it is not rendered at all: a withheld layer that other
                layers stand on has neither act available, and an empty flex row with a gap is a
                stray space between two sentences. */}
            {(layer.accepted || !layer.textWithheld) || !liveChildren.length ? (
            <div className="flex flex-wrap items-center gap-2">
              {layer.accepted ? (
                // WITHDRAW, NEVER A SECOND ACCEPT. The server refuses a second acceptance because it
                // would overwrite the first acceptor's name, so offering the button would offer a
                // refusal. Offered even when the text is withheld, deliberately and matching the
                // server: `unaccept` carries no media gate, because taking a name off states nothing
                // about the text and must stay reachable after a grant is withdrawn — otherwise an
                // acceptance is trapped in a report with nobody able to undo it.
                <button
                  type="button"
                  className="field-button-secondary"
                  disabled={anyBusy}
                  onClick={() => onWithdrawOpen(layer.id)}
                >
                  <Undo2 className="h-4 w-4" aria-hidden />
                  Withdraw my acceptance
                </button>
              ) : layer.textWithheld ? null : (
                <button type="button" className="field-button" disabled={anyBusy} onClick={() => onAccept(layer)}>
                  {busy ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <Check className="h-4 w-4" aria-hidden />}
                  {/* Worded, not only spun: the reduced-motion rules in globals.css zero every
                      animation-duration, so a frozen spinner is the whole of the feedback for the
                      readers who asked for less motion. */}
                  {busy ? "Accepting…" : "Accept this layer"}
                </button>
              )}
              {liveChildren.length ? null : (
                <button
                  type="button"
                  className="inline-flex items-center gap-1 text-xs font-medium text-ink-500 underline"
                  disabled={anyBusy}
                  onClick={() => onDecline(layer)}
                >
                  <Trash2 className="h-3 w-3" aria-hidden />
                  {busy ? "Declining…" : "Decline it"}
                </button>
              )}
            </div>
            ) : null}

            {/* NEITHER OF THE TWO SENTENCES BELOW IS A DISABLED BUTTON WITH A TOOLTIP. Each replaces
                a control whose ONLY possible outcome was the server's refusal — the same rule this
                file's header states for the second acceptance, applied to the two cases it missed. */}

            {!layer.accepted && layer.textWithheld ? (
              // ACCEPT IS A GUARANTEED 403 HERE, not a maybe. `textWithheld` on this row and the
              // refusal in `accept_ai_layer` are the same computation on the same server —
              // `media_roots` over the workshop's layers, then `owned_or_granted_where(user,
              // owner_field="uploadedById")` — so every row carrying the withheld notice above would
              // answer this button with a 403 and nothing else. Worse, the accept dialog's own advice
              // ("press Show the full text and read it before you accept") can never be followed for
              // one of these: `includeText` does not lift the withholding, and the route says so.
              <p className="text-xs leading-5 text-ink-500">
                Accepting is not offered for this one. An acceptance says a person read this text and the report prints
                their name beside it, and this account cannot open the recording it was made from. Ask whoever uploaded
                the recording for access to their media, or ask them to accept it themselves.
              </p>
            ) : null}

            {liveChildren.length ? (
              // AND DECLINING IS A GUARANTEED 409. `deletion_plan` refuses while live layers derive
              // from this one, naming their kinds, because the derived rows would be left describing
              // something no screen will show. Those rows are drawn inside this one, so the refusal
              // is predictable from what is already on the page — with one exception, which the last
              // sentence names: somebody else may have declined them since this list was read.
              <p className="text-xs leading-5 text-ink-500">
                Declining is not offered while{" "}
                {liveChildren.length === 1
                  ? `the ${layerKindNoun(liveChildren[0].layer.kind)} below`
                  : `${liveChildren.length} layers below`}{" "}
                {liveChildren.length === 1 ? "stands" : "stand"} on this one — {liveChildren.length === 1 ? "it" : "they"}{" "}
                would be left describing something no screen will show. Decline{" "}
                {liveChildren.length === 1 ? "it" : "those"} first. If somebody has already done so, press Reload above.
              </p>
            ) : null}
          </div>
        )}
      </div>

      {node.children.length ? (
        // THE CHAIN, DRAWN AS ONE. A derived layer sits INSIDE its source with a rule down the left,
        // so "what was this made from" is answered by the layout instead of by a cuid comparison.
        <ul className="mt-3 grid gap-3 border-l-2 border-line-200 pl-3">
          {node.children.map((child) => (
            <LayerRow
              key={child.layer.id}
              node={child}
              parentKind={layer.kind}
              withText={withText}
              userId={userId}
              busyLayerId={busyLayerId}
              anyBusy={anyBusy}
              decisions={decisions}
              withdrawing={withdrawing}
              onAccept={onAccept}
              onWithdrawOpen={onWithdrawOpen}
              onWithdraw={onWithdraw}
              onDecline={onDecline}
            />
          ))}
        </ul>
      ) : null}
    </li>
  );
}

/**
 * The reason a person took their name back off, typed inline rather than in a dialog.
 *
 * A WITHDRAWAL USUALLY HAS A REASON WORTH KEEPING — the schema's own comment gives the example, "the
 * model invented a village name" — and the reason is what stops the same layer being re-accepted by
 * somebody who was not told. `ConfirmOptions` carries no input, and bolting one into the shared
 * confirm dialog to serve one caller would change a primitive fifteen destructive handlers depend on.
 * So this is a small inline form, which also keeps the note visible while it is being written.
 *
 * Accepting, by contrast, needs no note: "I read it and it is right" is the whole message.
 */
function WithdrawForm({
  busy,
  onCancel,
  onSubmit
}: {
  busy: boolean;
  onCancel: () => void;
  onSubmit: (note: string) => void;
}) {
  const [note, setNote] = useState("");
  return (
    <div className="grid gap-2 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2">
      <p className="text-xs leading-5 text-amber-800">
        Taking your name off does not delete the layer and does not change any report already generated — that document
        named you, and it does not change under an officer who is holding it. Say why, if there is a reason worth keeping.
      </p>
      <label className="grid gap-1 text-xs font-medium text-amber-800">
        Why (optional)
        <textarea
          className="field-input min-h-16"
          value={note}
          maxLength={MAX_AI_LAYER_NOTE_CHARS}
          disabled={busy}
          onChange={(event) => setNote(event.currentTarget.value)}
          placeholder="The model invented a village name that is not in the recording."
          data-testid="ai-layer-withdraw-note"
        />
      </label>
      <div className="flex flex-wrap items-center gap-2">
        <button type="button" className="field-button" disabled={busy} onClick={() => onSubmit(note)}>
          {busy ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <Undo2 className="h-4 w-4" aria-hidden />}
          {/* Worded as well as spun — reduced motion freezes the spinner. */}
          {busy ? "Withdrawing…" : "Withdraw my acceptance"}
        </button>
        <button
          type="button"
          className="inline-flex items-center gap-1 text-xs font-medium text-amber-800 underline"
          disabled={busy}
          onClick={onCancel}
        >
          Keep it accepted
        </button>
      </div>
    </div>
  );
}

/** The structured half of a layer, drawn as what it is rather than summarised into a sentence. */
function PayloadView({ view }: { view: NonNullable<DwAiPayloadView> }) {
  if (view.shape === "TAGS") {
    return (
      <div className="flex flex-wrap gap-1.5">
        {view.tags.map((tag, index) => (
          <span
            key={`${tag}-${index}`}
            className="rounded-full border border-line-200 bg-surface-50 px-2 py-0.5 text-xs text-ink-700"
          >
            {tag}
          </span>
        ))}
      </div>
    );
  }
  if (view.shape === "ROWS") {
    return (
      <dl className="grid gap-x-4 gap-y-1 text-xs leading-5 sm:grid-cols-2">
        {view.rows.map((row) => (
          <div key={row.key} className="flex gap-1">
            <dt className="font-medium text-ink-700">{row.key}</dt>
            <dd className="text-ink-500">{row.value}</dd>
          </div>
        ))}
      </dl>
    );
  }
  return (
    <div>
      <pre className="max-h-60 overflow-auto rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs leading-5 text-ink-700">
        {view.json}
      </pre>
      {/* Every truncation says so. A payload that quietly stopped would be read as the whole answer. */}
      {view.truncated ? (
        <p className="mt-1 text-xs leading-5 text-ink-500">
          This is the opening of a longer payload; the rest is stored but is not shown here.
        </p>
      ) : null}
    </div>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * Registering what the server already holds
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The recordings whose transcripts have no layer yet.
 *
 * WITHOUT THIS THE SCREEN IS EMPTY FOR EVER on most deployments. Nothing writes a layer today —
 * generation is steps 7 and 8 of the plan — so the only rows that can exist are transcripts this
 * server produced months ago and has never attributed. That archive IS the "backlog of unattributed
 * AI text" the plan says to get provenance in ahead of, and it is one row deep.
 *
 * REGISTERING PRODUCES NO TEXT AND SPENDS NO PROVIDER CREDIT. The route reaches no model: it reads the
 * transcript the media queue already stored and records it, with its provenance, as one or two layers.
 */
function RegisterPanel({
  items,
  failed,
  loaded,
  layersKnown,
  busyMediaId,
  anyBusy,
  onRegister
}: {
  items: DwTranscriptItem[];
  failed: boolean;
  loaded: boolean;
  /** Whether this workshop's existing layers have been read — see the call site. */
  layersKnown: boolean;
  busyMediaId: string | null;
  anyBusy: boolean;
  onRegister: (item: DwTranscriptItem) => void;
}) {
  if (failed) {
    return (
      <div className="panel grid gap-2 p-4">
        <h3 className="font-display text-sm font-bold text-ink-900">The recordings could not be listed</h3>
        <p className="text-sm leading-6 text-ink-muted">
          So nothing can be offered for registration here. The layers above are unaffected — they are read from a different
          endpoint. Open the report screen&apos;s transcript annexure to see what state the recordings are in.
        </p>
      </div>
    );
  }
  if (!loaded) return null;

  return (
    <div className="panel grid gap-3 p-4">
      <div>
        <h3 className="font-display text-sm font-bold text-ink-900">Recordings with no layer yet</h3>
        <p className="mt-1 text-sm leading-6 text-ink-muted">
          Registering records a transcript this server produced <em>months ago and never attributed</em> as a layer, with
          its provenance. It reaches no provider, produces no new text and spends nothing — the words are already stored.
          On a default deployment it writes two rows, because the transcript was rewritten after the provider produced it
          and the two are different rungs of the same chain.
        </p>
        <p className="mt-2 text-sm leading-6 text-ink-muted">
          It will read as <strong>not recorded</strong> for the provider and the model, and that is the truthful answer:
          the transcription queue has never stored which of its four providers ran. There is deliberately no box here to
          type one into — a guess in that column is worse than an admitted gap.
        </p>
      </div>

      {!layersKnown ? (
        <p className="text-sm leading-6 text-ink-700">
          Waiting for this workshop&apos;s existing layers. Until they have been read, this cannot say which recordings
          already have one, so nothing is offered here — registering a recording twice is refused, and finding that out
          from a refusal is no way to be told.
        </p>
      ) : items.length ? (
        <ul className="grid gap-2">
          {items.map((item) => (
            <li
              key={item.mediaId}
              className={`grid gap-2 rounded-md border px-3 py-2 sm:flex sm:items-center sm:justify-between ${
                item.hasTranscript ? "border-line-200 bg-surface-50" : "border-amber-500/30 bg-amber-100"
              }`}
            >
              <div className="min-w-0">
                <p className="text-sm font-medium text-ink-900">
                  {item.stageNumber}. {item.label}
                </p>
                <p className="text-xs leading-5 text-ink-500">
                  {item.hasTranscript
                    ? `${item.durationText} · ${item.wordCount} words${item.speakerCount > 1 ? ` · ${item.speakerCount} voices` : ""}`
                    : // LISTED, not hidden: a designer who made six recordings and sees four concludes
                      // two were lost, when in fact two are still in the queue.
                      `Nothing to register yet — transcription is ${item.status.toLowerCase() || "not finished"}.`}
                </p>
              </div>
              {item.hasTranscript ? (
                <button
                  type="button"
                  className="field-button-secondary shrink-0"
                  // Every action on the page, not only the other Register buttons: a registration
                  // running while an acceptance is in flight would overwrite its notice and race its
                  // refresh. See `anyBusy` in the panel.
                  disabled={anyBusy}
                  onClick={() => onRegister(item)}
                >
                  {busyMediaId === item.mediaId ? (
                    <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
                  ) : (
                    <Plus className="h-4 w-4" aria-hidden />
                  )}
                  {busyMediaId === item.mediaId ? "Registering…" : "Register its transcript"}
                </button>
              ) : (
                <span className="flex shrink-0 items-center gap-1 text-xs font-medium text-amber-800">
                  <AlertTriangle className="h-3.5 w-3.5" aria-hidden />
                  Waiting on the queue
                </span>
              )}
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-sm leading-6 text-ink-700">
          Every recording this workshop holds is already registered as a layer, or the workshop has no recordings.
        </p>
      )}
    </div>
  );
}
