"use client";

import { Toggle } from "@/components/ui/Toggle";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  AlertTriangle,
  Check,
  CheckCircle2,
  Download,
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
import { AiVerbReviewDialog } from "@/components/designworkshop/AiVerbReviewDialog";
import { useWorkshopConsent } from "@/components/hooks/useWorkshopConsent";
import { useAuth } from "@/components/AuthProvider";
import { useConfirm } from "@/components/dialogs/ConfirmDialog";
import { formatDateTime } from "@/lib/format";
import {
  AI_VERB_COUNTDOWN_FROM,
  SUBTITLE_FORMAT_LABELS,
  downloadDesignWorkshopSubtitles,
  dwAiVerbAllowance,
  isVerbOffline,
  proofreadDesignWorkshopText,
  subtitleCueSummary,
  translateDesignWorkshopText,
  translationTargetRefusal,
  verbAllowanceRefusal,
  verbWorkshopRefusal,
  VERBS_NEED_A_CONNECTION,
  WORKSHOP_NOT_ON_SERVER_YET,
  type DwAiVerbAllowanceState,
  type DwAiVerbResult,
  type DwSubtitleFormat
} from "@/lib/aiVerbs";
import { saveBlobToDisk } from "@/lib/designWorkshops";
import {
  MAX_AI_LAYER_NOTE_CHARS,
  acceptDesignWorkshopAiLayer,
  aiLayerProblem,
  decisionActor,
  decisionLabel,
  deleteDesignWorkshopAiLayer,
  getDesignWorkshopAiLayer,
  flattenAiLayerNodes,
  groupAiLayers,
  layerKindLabel,
  layerKindNote,
  layerKindNoun,
  aiVerbPlacementAllows,
  layerProvenance,
  listDesignWorkshopAiLayerDecisions,
  listDesignWorkshopAiLayers,
  readAiPayload,
  registerDesignWorkshopAiLayer,
  tierLabel,
  tierSentence,
  unacceptDesignWorkshopAiLayer,
  type DwAiDecisionHistory,
  type DwAiDecisionRecord,
  type DwAiLayer,
  type DwAiLayerList,
  type DwAiLayerNode,
  type DwAiPayloadView
} from "@/lib/aiLayers";
import { listDesignWorkshopTranscripts, type DwTranscriptItem, type DwTranscriptList } from "@/lib/designWorkshops";
import { ActionGroup } from "@/components/ui/ActionGroup";

type DecisionLog = Record<string, DwAiDecisionRecord[]>;

type OneLayerReads = {
  texts: Record<string, string>;
  readingId: string | null;
  onRead: (layer: DwAiLayer) => void;
};

export function AiLayersPanel({
  workshopId,
  readOnlyReason = null
}: {
  workshopId: string;
  readOnlyReason?: string | null;
}) {
  const { user } = useAuth();
  const confirm = useConfirm();

  const [list, setList] = useState<DwAiLayerList | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [includeDeleted, setIncludeDeleted] = useState(false);
  const [withText, setWithText] = useState(false);
  const [busyLayerId, setBusyLayerId] = useState<string | null>(null);
  const [decisions, setDecisions] = useState<DecisionLog>({});
  const [oneTexts, setOneTexts] = useState<Record<string, string>>({});
  const [readingId, setReadingId] = useState<string | null>(null);
  const [history, setHistory] = useState<DwAiDecisionHistory | null>(null);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [withdrawing, setWithdrawing] = useState<string | null>(null);

  const [verbAllowance, setVerbAllowance] = useState<DwAiVerbAllowanceState | null>(null);
  const consent = useWorkshopConsent(workshopId);
  const [verbResult, setVerbResult] = useState<DwAiVerbResult | null>(null);

  const [transcripts, setTranscripts] = useState<DwTranscriptList | null>(null);
  const [transcriptsFailed, setTranscriptsFailed] = useState(false);
  const [registering, setRegistering] = useState<string | null>(null);

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
            "Couldn't load the AI layers."
          )
        );
      } finally {
        if (seq === loadSeq.current) setLoading(false);
      }
    },
    [workshopId]
  );

  useEffect(() => {
    void load({ includeDeleted, withText });
  }, [load, includeDeleted, withText]);

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

  useEffect(() => {
    let cancelled = false;
    void dwAiVerbAllowance().then((answer) => {
      if (!cancelled) setVerbAllowance(answer);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const verbsBlocked =
    readOnlyReason ??
    verbWorkshopRefusal({ ready: consent.ready, serverId: consent.serverId, decision: consent.decision }) ??
    verbAllowanceRefusal(verbAllowance);

  const grouping = useMemo(() => groupAiLayers(list?.items ?? []), [list]);

  const recordingsById = useMemo(() => {
    const byId = new Map<string, DwTranscriptItem>();
    for (const item of transcripts?.items ?? []) byId.set(item.mediaId, item);
    return byId;
  }, [transcripts]);

  const tiersPresent = useMemo(() => {
    const seen: string[] = [];
    for (const layer of list?.items ?? []) if (!seen.includes(layer.tier)) seen.push(layer.tier);
    return seen;
  }, [list]);

  const registerable = useMemo(() => {
    const already = new Set(
      (list?.items ?? [])
        .filter((layer) => layer.source?.kind === "MEDIA" && !layer.deletedAt)
        .map((layer) => layer.source?.id ?? "")
    );
    return (transcripts?.items ?? []).filter((item) => !already.has(item.mediaId));
  }, [list, transcripts]);

  const refresh = useCallback(() => load({ includeDeleted, withText }), [load, includeDeleted, withText]);

  const readOne = useCallback(
    async (layer: DwAiLayer) => {
      setReadingId(layer.id);
      try {
        const answer = await getDesignWorkshopAiLayer(workshopId, layer.id);
        if (typeof answer.layer.text === "string") {
          setOneTexts((current) => ({ ...current, [layer.id]: answer.layer.text as string }));
        }
        setDecisions((current) => ({ ...current, [layer.id]: answer.decisions }));
      } catch (err) {
        setError(aiLayerProblem(err, "Couldn't read that layer. Reload and try again."));
      } finally {
        setReadingId(null);
      }
    },
    [workshopId]
  );

  const loadHistory = useCallback(async () => {
    setHistoryLoading(true);
    try {
      setHistory(await listDesignWorkshopAiLayerDecisions(workshopId));
    } catch (err) {
      setError(aiLayerProblem(err, "Couldn't load the history. Try again."));
    } finally {
      setHistoryLoading(false);
    }
  }, [workshopId]);

  const afterDecision = useCallback(
    async (layer: DwAiLayer) => {
      await readOne(layer);
      if (historyOpen) await loadHistory();
    },
    [readOne, historyOpen, loadHistory]
  );

  const oneLayer: OneLayerReads = { texts: oneTexts, readingId, onRead: (layer) => void readOne(layer) };

  const anyBusy = busyLayerId !== null || registering !== null;
  const locked = anyBusy || Boolean(readOnlyReason);

  async function accept(layer: DwAiLayer) {
    const provenance = layerProvenance(layer);
    const shown = withText ? layer.text : oneTexts[layer.id];
    const readable = typeof shown === "string" && shown.trim().length > 0;
    const agreed = await confirm({
      title: `Accept this ${layerKindNoun(layer.kind)} in your name?`,
      tone: "warning",
      confirmLabel: "I have read it — accept it in my name",
      cancelLabel: "Not yet",
      body: (
        <>
          Your name is recorded as the person who read and stands behind this text. Accepted layers can be printed in a
          report, marked as AI-produced.{" "}
          {provenance.unrecorded
            ? "No provider or model was recorded."
            : `It was produced by ${provenance.provider}, model ${provenance.model}, ${tierLabel(layer.tier).toLowerCase()}.`}
        </>
      ),
      note: (
        <>
          {readable
            ? null
            : "Read the full text first — press “Read this layer” on its row. "}
          You can withdraw later; reports already made will still show your acceptance.
        </>
      )
    });
    if (!agreed) return;

    setBusyLayerId(layer.id);
    setError(null);
    setNotice(null);
    try {
      await acceptDesignWorkshopAiLayer(workshopId, layer.id);
      setNotice(`Accepted under your name.`);
      await refresh();
      await afterDecision(layer);
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
      await unacceptDesignWorkshopAiLayer(workshopId, layer.id, note);
      setWithdrawing(null);
      setNotice(
        "Acceptance withdrawn. Reports already made still show it as accepted."
      );
      await refresh();
      await afterDecision(layer);
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
          It won&apos;t be printed. It is kept as a record that it was declined.
        </>
      ),
      note: (
        <>
          The source is not changed.
        </>
      )
    });
    if (!agreed) return;

    setBusyLayerId(layer.id);
    setError(null);
    setNotice(null);
    try {
      await deleteDesignWorkshopAiLayer(workshopId, layer.id);
      setNotice("Declined.");
      await refresh();
      if (historyOpen) await loadHistory();
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
      const result = await registerDesignWorkshopAiLayer(workshopId, { sourceMediaId: item.mediaId });
      setNotice(
        result.total > 1
          ? `Registered as ${result.total} layers. Accept them to print.`
          : "Registered. Accept it to print."
      );
      await refresh();
    } catch (err) {
      setError(aiLayerProblem(err, "Couldn't register that recording."));
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
          <span className="mt-0.5 grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-field-200 text-field-600 dark:text-purple-300">
            <Layers className="h-4 w-4" aria-hidden />
          </span>
          <div className="min-w-0">
            <h2 className="font-display text-base font-bold text-ink-900">What a model produced, and who accepted it</h2>
            <p className="mt-1 text-sm leading-6 text-ink-muted">
              AI-made text from this workshop&apos;s material. <strong>Nothing is printed until a person accepts it.</strong>
            </p>
            <p className="mt-2 text-sm leading-6 text-ink-muted">
              They never fill in a stage field.
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
          {verbAllowance && verbAllowance.aiVerbsLimit !== null ? (
            <span
              className={
                verbAllowance.aiVerbsRemaining !== null && verbAllowance.aiVerbsRemaining <= AI_VERB_COUNTDOWN_FROM
                  ? "font-medium text-amber-800 dark:text-amber-100"
                  : "text-ink-700"
              }
            >
              <strong className="text-ink-900">{verbAllowance.aiVerbsRemaining ?? 0}</strong> of{" "}
              {verbAllowance.aiVerbsLimit} AI runs left for {verbAllowance.aiVerbDay}
            </span>
          ) : null}
          <Toggle
            label="Show declined layers"
            checked={includeDeleted}
            onChange={setIncludeDeleted}
            testId="ai-layers-include-deleted"
          />
          <ActionGroup className="max-sm:w-full"><button type="button" className="field-button-secondary" onClick={() => setWithText((current) => !current)}>
            {withText ? "Hide the full text" : "Show the full text"}
          </button>
          <button type="button" className="field-button-secondary" onClick={() => void refresh()} disabled={loading}>
            <RefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} aria-hidden />
            {loading ? "Reading…" : "Reload"}
          </button></ActionGroup>
        </div>

        {!withText ? (
          <p className="text-xs leading-5 text-ink-500">
            Or press “Read this layer” on one row.
          </p>
        ) : null}

        <div className="grid gap-2 border-t border-line-200 pt-3">
          <button
            type="button"
            className="field-button-secondary justify-self-start"
            aria-expanded={historyOpen}
            disabled={historyLoading}
            onClick={() => {
              const next = !historyOpen;
              setHistoryOpen(next);
              if (next) void loadHistory();
            }}
            data-testid="ai-layers-history-toggle"
          >
            {historyLoading ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : null}
            {historyOpen ? "Hide the decision history" : "Show the decision history"}
          </button>
          {historyOpen && history ? (
            history.items.length ? (
              <ul className="grid gap-1" data-testid="ai-layers-history">
                {history.items.map((entry) => (
                  <li key={entry.id} className="text-xs leading-5 text-ink-700">
                    <span className="font-medium text-ink-900">{decisionLabel(entry.decision)}</span> ·{" "}
                    {layerKindLabel(entry.layerKind ?? null)} · {formatDateTime(entry.createdAt)} · by{" "}
                    {decisionActor(entry, user?.id ?? null)}
                    {entry.note ? ` · “${entry.note}”` : ""}
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-xs leading-5 text-ink-500">
                No decisions yet.
              </p>
            )
          ) : null}
        </div>

        <p className="flex items-start gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
          <Info className="mt-1 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
          <span>
            Accepting signs off the text for print. Reports label it as AI-assisted, with the model and your name.
          </span>
        </p>

        {error ? (
          <p role="alert" className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm leading-6 text-red-700">
            {consent.ready && !consent.serverId ? WORKSHOP_NOT_ON_SERVER_YET : error}
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

      {list && !grouping.recordings.length && !grouping.notes.length && !grouping.unrooted.length ? (
        <div className="panel grid gap-2 p-4">
          <h3 className="font-display text-sm font-bold text-ink-900">Nothing has been registered yet</h3>
          <p className="text-sm leading-6 text-ink-muted">
            {includeDeleted
              ? "No AI layers yet."
              : "No AI layers yet. Tick “Show declined layers” to see declined ones."}
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
                    ? "Couldn't load the recording's name."
                    :
                      "A photograph, or a recording you can't access."}
                {" · "}
                <span className="font-mono">{group.mediaId}</span>
              </p>
              <p className="mt-1 text-xs leading-5 text-ink-500">
                {layersHere} layer{layersHere === 1 ? "" : "s"} stand
                {layersHere === 1 ? "s" : ""} on it
                {group.roots.length > 1
                  ? ` in ${group.roots.length} chains.`
                  : "."}
              </p>
            </div>

            <ul className="grid gap-3">
              {group.roots.map((node) => (
                <LayerRow
                  key={node.layer.id}
                  node={node}
                  workshopId={workshopId}
                  verbsBlocked={verbsBlocked}
                  onVerbProduced={setVerbResult}
                  parentKind={null}
                  withText={withText}
                  userId={user?.id ?? null}
                  busyLayerId={busyLayerId}
                  anyBusy={locked}
                  decisions={decisions}
                  oneLayer={oneLayer}
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

      {grouping.notes.length ? (
        <div className="panel grid gap-3 p-4">
          <div>
            <h3 className="font-display text-sm font-bold text-ink-900">
              {grouping.notes.length} layer{grouping.notes.length === 1 ? "" : "s"} made from words typed into this
              workshop
            </h3>
            <p className="mt-1 text-sm leading-6 text-ink-muted">
              Made from text a designer sent, not a recording. The original words are kept.
            </p>
          </div>
          <ul className="grid gap-3">
            {grouping.notes.map((node) => (
              <LayerRow
                key={node.layer.id}
                node={node}
                workshopId={workshopId}
                verbsBlocked={verbsBlocked}
                onVerbProduced={setVerbResult}
                parentKind={null}
                withText={withText}
                userId={user?.id ?? null}
                busyLayerId={busyLayerId}
                anyBusy={locked}
                decisions={decisions}
                oneLayer={oneLayer}
                withdrawing={withdrawing}
                onAccept={accept}
                onWithdrawOpen={setWithdrawing}
                onWithdraw={withdraw}
                onDecline={decline}
              />
            ))}
          </ul>
        </div>
      ) : null}

      {grouping.unrooted.length ? (
        <div className="panel grid gap-3 p-4">
          <div>
            <h3 className="font-display text-sm font-bold text-ink-900">
              {grouping.unrooted.length} layer{grouping.unrooted.length === 1 ? "" : "s"} could not be attached to a
              recording
            </h3>
            <p className="mt-1 text-sm leading-6 text-ink-muted">
              They are still listed here, so you can review them.
            </p>
          </div>
          <ul className="grid gap-3">
            {grouping.unrooted.map((entry) => (
              <li key={entry.node.layer.id} className="grid gap-2">
                <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
                  {entry.reason === "NO_SOURCE"
                    ? "Source missing — it can't be printed. Ask an admin to check it."
                    : entry.reason === "SOURCE_NOT_LISTED"
                      ? "Its source isn't listed — it may have been declined. Tick “Show declined layers”."
                      : entry.reason === "UNKNOWN_SOURCE"
                        ?
                          "Its source can't be shown. Reload to update the app."
                        : "Source can't be found — it can't be printed."}
                </p>
                <ul className="grid gap-3">
                  <LayerRow
                    node={entry.node}
                    workshopId={workshopId}
                    verbsBlocked={verbsBlocked}
                    onVerbProduced={setVerbResult}
                    parentKind={null}
                    withText={withText}
                    userId={user?.id ?? null}
                    busyLayerId={busyLayerId}
                    anyBusy={locked}
                    decisions={decisions}
                    oneLayer={oneLayer}
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
            The phone works offline; the cloud knows craft words like <em>dabu</em>.
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
        layersKnown={list !== null}
        busyMediaId={registering}
        anyBusy={locked}
        onRegister={register}
      />

      <AiVerbReviewDialog
        open={verbResult !== null}
        workshopId={workshopId}
        result={verbResult}
        onAccepted={() => {
          setVerbResult(null);
          void refresh();
        }}
        onDeclined={() => {
          setVerbResult(null);
          void refresh();
        }}
        onClose={() => {
          setVerbResult(null);
          void refresh();
        }}
      />
    </section>
  );
}

function LayerRow({
  node,
  workshopId,
  verbsBlocked,
  onVerbProduced,
  parentKind,
  withText,
  userId,
  busyLayerId,
  anyBusy,
  decisions,
  oneLayer,
  withdrawing,
  onAccept,
  onWithdrawOpen,
  onWithdraw,
  onDecline
}: {
  node: DwAiLayerNode;
  workshopId: string;
  verbsBlocked: string | null;
  onVerbProduced: (result: DwAiVerbResult) => void;
  parentKind: string | null;
  withText: boolean;
  userId: string | null;
  busyLayerId: string | null;
  anyBusy: boolean;
  decisions: DecisionLog;
  oneLayer: OneLayerReads;
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
  const history = decisions[layer.id] ?? null;
  const reading = oneLayer.readingId === layer.id;
  const text = withText ? layer.text : oneLayer.texts[layer.id];

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
              {parentKind
                ? `Produced from the ${layerKindNoun(parentKind)} above it.`
                : "Produced from the recording or photograph itself."}
              {note ? ` ${note}` : " Reload to update the app."}
            </p>
          </div>
          <div className="flex shrink-0 flex-wrap items-center gap-1.5">
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
            No provider or model was recorded.
          </p>
        ) : null}

        {layer.accepted ? (
          <p className="text-xs leading-5 text-ink-700">
            Accepted {formatDateTime(layer.acceptedAt)}{" "}
            {layer.acceptedById
              ? layer.acceptedById === userId
                ? "by you."
                :
                  "by another account."
              : "— who accepted it wasn't recorded."}
            {layer.acceptedById && layer.acceptedById !== userId ? (
              <span className="ml-1 font-mono text-ink-500">{layer.acceptedById}</span>
            ) : null}
          </p>
        ) : null}

        {layer.textWithheld ? (
          <p className="flex items-start gap-2 rounded-md border border-line-200 bg-surface-50 px-2 py-1.5 text-xs leading-5 text-ink-700">
            <EyeOff className="mt-0.5 h-3.5 w-3.5 shrink-0 text-ink-500" aria-hidden />
            <span>
              Text hidden — you can&apos;t access the source recording.
            </span>
          </p>
        ) : typeof text === "string" && text.trim() ? (
          <div className="max-h-80 overflow-y-auto rounded-md border border-line-200 bg-surface-50 px-3 py-2">
            <Markdown text={text} />
          </div>
        ) : layer.preview ? (
          <p className="line-clamp-2 text-xs leading-5 text-ink-500">{layer.preview}</p>
        ) : layer.textChars === 0 ? (
          <p className="text-xs leading-5 text-ink-500">No text; content below.</p>
        ) : null}

        {!layer.textWithheld && layer.textChars ? (
          <p className="flex flex-wrap items-center gap-x-2 text-xs leading-5 text-ink-500">
            {layer.textChars.toLocaleString("en-IN")} characters
            {typeof text === "string" ? null : (
              <button
                type="button"
                className="inline-flex items-center gap-1 font-medium text-ink-700 underline"
                disabled={reading}
                onClick={() => oneLayer.onRead(layer)}
              >
                {reading ? <Loader2 className="h-3 w-3 animate-spin" aria-hidden /> : null}
                {reading ? "Reading…" : "Read this layer"}
              </button>
            )}
          </p>
        ) : null}

        {payload ? <PayloadView view={payload} /> : null}

        {history ? (
          <div className="rounded-md border border-line-200 bg-surface-50 px-2 py-1.5" data-testid="ai-layer-history">
            <p className="text-xs font-medium text-ink-700">Decision history</p>
            {history.length ? (
              <ul className="mt-1 grid gap-0.5">
                {history.map((entry) => (
                  <li key={entry.id} className="text-xs leading-5 text-ink-500">
                    {decisionLabel(entry.decision)} · {formatDateTime(entry.createdAt)} · by {decisionActor(entry, userId)}
                    {entry.note ? ` · “${entry.note}”` : ""}
                  </li>
                ))}
              </ul>
            ) : (
              <p className="mt-1 text-xs leading-5 text-ink-500">Nobody has accepted, withdrawn or declined this layer.</p>
            )}
          </div>
        ) : (
          <button
            type="button"
            className="inline-flex items-center gap-1 justify-self-start text-xs font-medium text-ink-700 underline"
            disabled={reading}
            onClick={() => oneLayer.onRead(layer)}
          >
            {reading ? <Loader2 className="h-3 w-3 animate-spin" aria-hidden /> : null}
            Show its decision history
          </button>
        )}

        {declined ? (
          <p className="text-xs leading-5 text-ink-500">
            Declined. Register the material again if you still want it.
          </p>
        ) : withdrawing === layer.id ? (
          <WithdrawForm busy={busy} onCancel={() => onWithdrawOpen(null)} onSubmit={(text) => onWithdraw(layer, text)} />
        ) : (
          <div className="grid gap-2">
            {(layer.accepted || !layer.textWithheld) || !liveChildren.length ? (
            <ActionGroup>
              {layer.accepted ? (
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
            </ActionGroup>
            ) : null}

            {!layer.accepted && layer.textWithheld ? (
              <p className="text-xs leading-5 text-ink-500">
                You can&apos;t access the source recording. Ask its uploader for access.
              </p>
            ) : null}

            {liveChildren.length ? (
              <p className="text-xs leading-5 text-ink-500">
                First decline{" "}
                {liveChildren.length === 1
                  ? `the ${layerKindNoun(liveChildren[0].layer.kind)} below`
                  : `the ${liveChildren.length} layers below`}
                .
              </p>
            ) : null}
          </div>
        )}
        <LayerVerbs
          layer={layer}
          workshopId={workshopId}
          blocked={verbsBlocked}
          onProduced={onVerbProduced}
        />
      </div>

      {node.children.length ? (
        <ul className="mt-3 grid gap-3 rounded-lg bg-surface-50 p-3">
          {node.children.map((child) => (
            <LayerRow
              key={child.layer.id}
              node={child}
              workshopId={workshopId}
              verbsBlocked={verbsBlocked}
              onVerbProduced={onVerbProduced}
              parentKind={layer.kind}
              withText={withText}
              userId={userId}
              busyLayerId={busyLayerId}
              anyBusy={anyBusy}
              decisions={decisions}
              oneLayer={oneLayer}
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

function LayerVerbs({
  layer,
  workshopId,
  blocked,
  onProduced
}: {
  layer: DwAiLayer;
  workshopId: string;
  blocked: string | null;
  onProduced: (result: DwAiVerbResult) => void;
}) {
  const [running, setRunning] = useState<"PROOFREAD" | "TRANSLATE" | "DOWNLOAD" | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [target, setTarget] = useState<string | null>(null);
  const [speakers, setSpeakers] = useState(false);

  const kind = String(layer.kind ?? "");
  const declined = layer.deletedAt !== null;
  const cues = kind === "SUBTITLES" ? subtitleCueSummary(layer.payload) : null;
  const canProofread = aiVerbPlacementAllows("PROOFREAD", kind);
  const canTranslate = aiVerbPlacementAllows("TRANSLATION", kind);

  async function run(verb: "PROOFREAD" | "TRANSLATE", targetLanguage?: string) {
    setRunning(verb);
    setProblem(null);
    try {
      const answer =
        verb === "PROOFREAD"
          ? await proofreadDesignWorkshopText(workshopId, { sourceLayerId: layer.id })
          : await translateDesignWorkshopText(workshopId, { sourceLayerId: layer.id }, targetLanguage ?? "");
      setTarget(null);
      onProduced(answer);
    } catch (err) {
      setProblem(isVerbOffline(err) ? VERBS_NEED_A_CONNECTION : aiLayerProblem(err, "That could not be done."));
    } finally {
      setRunning(null);
    }
  }

  async function download(format: DwSubtitleFormat) {
    setRunning("DOWNLOAD");
    setProblem(null);
    try {
      const file = await downloadDesignWorkshopSubtitles(workshopId, layer.id, format, speakers);
      saveBlobToDisk(file.blob, file.fileName);
    } catch (err) {
      setProblem(aiLayerProblem(err, "That subtitle file could not be produced."));
    } finally {
      setRunning(null);
    }
  }

  if (declined) return null;

  const offersVerbs = canProofread || canTranslate;
  if (!offersVerbs && !cues) return null;

  return (
    <div className="grid gap-2 border-t border-line-200 pt-2">
      {cues ? (
        <div className="grid gap-1.5">
          <p className="text-xs leading-5 text-ink-500">
            {cues.count.toLocaleString()} cue{cues.count === 1 ? "" : "s"}
            {cues.estimatedCues ? `, ${cues.estimatedCues.toLocaleString()} of them approximate` : ""}
            {cues.language ? ` \u00b7 ${cues.language}` : ""}. Download and watch them against the recording — this
            changes nothing and accepts nothing.
          </p>
          {cues.hasSpeakers ? (
            <Toggle
              label="Label speakers on each line (a best guess)"
              checked={speakers}
              onChange={setSpeakers}
            />
          ) : null}
          <div className="flex flex-wrap gap-2">
            {(["srt", "vtt"] as DwSubtitleFormat[]).map((format) => (
              <button
                key={format}
                type="button"
                className="field-button-secondary"
                disabled={running !== null || !cues.cues.length}
                onClick={() => void download(format)}
              >
                <Download className="h-4 w-4" aria-hidden />
                {SUBTITLE_FORMAT_LABELS[format]}
              </button>
            ))}
          </div>
        </div>
      ) : null}

      {offersVerbs ? (
        layer.textWithheld ? (
          <p className="text-xs leading-5 text-ink-500">
            You can&apos;t access the source recording. Ask its uploader for access.
          </p>
        ) : blocked !== null ? (
          <p className="text-xs leading-5 text-ink-500">{blocked}</p>
        ) : (
          <>
            <ActionGroup>
              {canProofread ? (
                <button
                  type="button"
                  className="field-button-secondary"
                  disabled={running !== null || blocked !== null}
                  onClick={() => void run("PROOFREAD")}
                >
                  {running === "PROOFREAD" ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : null}
                  {running === "PROOFREAD" ? "Proofreading\u2026" : "Proofread this"}
                </button>
              ) : null}
              {canTranslate ? (
                <button
                  type="button"
                  className="field-button-secondary"
                  disabled={running !== null || blocked !== null}
                  onClick={() => setTarget("")}
                >
                  Translate this
                </button>
              ) : null}
            </ActionGroup>
            {target !== null ? (
              <div className="grid gap-1.5">
                <label className="field-label" htmlFor={`ai-translate-${layer.id}`}>
                  Translate into
                </label>
                <input
                  id={`ai-translate-${layer.id}`}
                  className="field-input"
                  value={target}
                  maxLength={40}
                  placeholder="Odia, Hindi, English…"
                  onChange={(event) => setTarget(event.target.value)}
                />
                <p className="text-xs leading-5 text-ink-500">
                  Added beside the original, never replacing it.
                </p>
                {target.trim() && translationTargetRefusal(target) ? (
                  <p className="text-xs leading-5 text-error-600">{translationTargetRefusal(target)}</p>
                ) : null}
                <ActionGroup>
                  <button
                    type="button"
                    className="field-button"
                    disabled={running !== null || blocked !== null || translationTargetRefusal(target) !== null}
                    onClick={() => void run("TRANSLATE", target.trim())}
                  >
                    {running === "TRANSLATE" ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : null}
                    {running === "TRANSLATE" ? "Translating\u2026" : "Translate this layer"}
                  </button>
                  <button
                    type="button"
                    className="field-button-secondary"
                    disabled={running !== null}
                    onClick={() => setTarget(null)}
                  >
                    Cancel
                  </button>
                </ActionGroup>
              </div>
            ) : null}
          </>
        )
      ) : null}

      {problem ? (
        <p role="alert" className="text-xs leading-5 text-error-600">
          {problem}
        </p>
      ) : null}
    </div>
  );
}

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
      <p className="text-xs leading-5 text-amber-800 dark:text-amber-100">
        Reports already made are not changed. Reason (optional):
      </p>
      <label className="grid gap-1 text-xs font-medium text-amber-800 dark:text-amber-100">
        Why (optional)
        <textarea
          className="field-input min-h-16"
          value={note}
          maxLength={MAX_AI_LAYER_NOTE_CHARS}
          disabled={busy}
          onChange={(event) => setNote(event.currentTarget.value)}
          placeholder="e.g. invented a village name"
          data-testid="ai-layer-withdraw-note"
        />
      </label>
      <ActionGroup>
        <button type="button" className="field-button" disabled={busy} onClick={() => onSubmit(note)}>
          {busy ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <Undo2 className="h-4 w-4" aria-hidden />}
          {busy ? "Withdrawing…" : "Withdraw my acceptance"}
        </button>
        <button
          type="button"
          className="inline-flex items-center gap-1 text-xs font-medium text-amber-800 dark:text-amber-100 underline"
          disabled={busy}
          onClick={onCancel}
        >
          Keep it accepted
        </button>
      </ActionGroup>
    </div>
  );
}

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
      {view.truncated ? (
        <p className="mt-1 text-xs leading-5 text-ink-500">
          Showing the start only.
        </p>
      ) : null}
    </div>
  );
}

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
  layersKnown: boolean;
  busyMediaId: string | null;
  anyBusy: boolean;
  onRegister: (item: DwTranscriptItem) => void;
}) {
  if (failed) {
    return (
      <div className="panel grid gap-2 p-4">
        <h3 className="font-display text-sm font-bold text-ink-900">The recordings couldn&apos;t be loaded</h3>
        <p className="text-sm leading-6 text-ink-muted">
          Reload to try again.
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
          Adds a recording&apos;s existing transcript as a layer. Runs no AI.
        </p>
        <p className="mt-2 text-sm leading-6 text-ink-muted">
          The provider and the model will show as <strong>not recorded</strong> for these transcripts.
        </p>
      </div>

      {!layersKnown ? (
        <p className="text-sm leading-6 text-ink-700">
          Loading…
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
                    :
                      `Nothing to register yet — transcription is ${item.status.toLowerCase() || "not finished"}.`}
                </p>
              </div>
              {item.hasTranscript ? (
                <button
                  type="button"
                  className="field-button-secondary shrink-0"
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
                <span className="flex shrink-0 items-center gap-1 text-xs font-medium text-amber-800 dark:text-amber-100">
                  <AlertTriangle className="h-3.5 w-3.5" aria-hidden />
                  Waiting for transcription
                </span>
              )}
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-sm leading-6 text-ink-700">
          Nothing to register.
        </p>
      )}
    </div>
  );
}
