package com.oneai.chat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.oneai.data.Conversation;
import com.oneai.data.Message;
import com.oneai.data.Prefs;
import com.oneai.data.Store;
import com.oneai.net.Transport;
import com.oneai.net.android.TransportFactory;
import com.oneai.provider.ChatClient;
import com.oneai.provider.ChatMessage;
import com.oneai.provider.ChatRequest;
import com.oneai.provider.Provider;
import com.oneai.provider.ProviderException;
import com.oneai.provider.Providers;
import com.oneai.provider.StreamListener;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs one request on a background thread and delivers the result to the UI
 * thread.
 *
 * <p>Deliberately not {@code AsyncTask}: its execution semantics changed
 * between API 10 and API 11 (serial to parallel and back), so identical code
 * behaves differently on old and new devices. A single-thread executor plus a
 * main-thread Handler behaves the same everywhere, which is the entire premise
 * of this app.
 */
public final class StreamRunner {

    public interface Callback {
        void onText(String delta);

        void onThinking(String delta);

        void onDone(String stopReason, int inputTokens, int outputTokens);

        void onError(ProviderException error);
    }

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile ChatClient.Session session;
    private volatile boolean stopped;

    public StreamRunner(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** Aborts the in-flight request, if any, and suppresses further callbacks. */
    public void cancel() {
        stopped = true;
        ChatClient.Session s = session;
        if (s != null) {
            s.cancel();
        }
        executor.shutdownNow();
    }

    public boolean isRunning() {
        ChatClient.Session s = session;
        return s != null && !s.isCancelled() && !stopped;
    }

    /**
     * Sends {@code history} to the conversation's provider.
     *
     * <p>All prefs are read here, on the caller's thread, so the background
     * thread never touches SharedPreferences mid-request.
     */
    public void start(final Conversation conversation, final List<Message> history,
                      final Callback callback) {
        stopped = false;

        final Prefs prefs = Store.prefs(appContext);
        final Provider provider = Providers.byIdOrDefault(conversation.provider);
        final String apiKey = prefs.apiKey(provider.id());
        final String baseUrl = prefs.baseUrl(provider);
        final String model = resolveModel(prefs, conversation, provider);
        final ChatRequest request = buildRequest(prefs, conversation, provider, model, history);

        if (apiKey.length() == 0) {
            callback.onError(new ProviderException(ProviderException.NO_STATUS, null, null,
                    "No API key set for " + provider.displayName()
                            + ". Open Settings to add one.", false));
            return;
        }

        final ChatClient.Session s = new ChatClient.Session();
        session = s;

        final Transport transport = TransportFactory.create(appContext, prefs);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                // Providers report usage just before the stream ends, so it is
                // collected here and handed over with the completion callback.
                final int[] usage = new int[2];
                try {
                    new ChatClient(transport).stream(provider, apiKey, baseUrl, request,
                            new StreamListener() {
                                @Override
                                public void onText(String delta) {
                                    postText(callback, delta);
                                }

                                @Override
                                public void onThinking(String delta) {
                                    if (prefs.showThinking()) {
                                        postThinking(callback, delta);
                                    }
                                }

                                @Override
                                public void onUsage(int in, int out) {
                                    usage[0] = in;
                                    usage[1] = out;
                                }

                                @Override
                                public void onDone(String stopReason) {
                                    // Handled by postDone below, which runs even
                                    // when the stream ends without one.
                                }
                            }, s);
                    postDone(callback, usage[0], usage[1]);
                } catch (final ProviderException e) {
                    postError(callback, e);
                } catch (Throwable t) {
                    postError(callback, new ProviderException(ProviderException.NO_STATUS, null,
                            String.valueOf(t), "Unexpected failure: " + t, false));
                }
            }
        });
    }

    // ------------------------------------------------------------------

    private void postText(final Callback cb, final String delta) {
        if (stopped) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!stopped) {
                    cb.onText(delta);
                }
            }
        });
    }

    private void postThinking(final Callback cb, final String delta) {
        if (stopped) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!stopped) {
                    cb.onThinking(delta);
                }
            }
        });
    }

    private void postDone(final Callback cb, final int inputTokens, final int outputTokens) {
        if (stopped) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!stopped) {
                    cb.onDone(null, inputTokens, outputTokens);
                }
            }
        });
    }

    private void postError(final Callback cb, final ProviderException e) {
        if (stopped) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!stopped) {
                    cb.onError(e);
                }
            }
        });
    }

    // ------------------------------------------------------------- request

    static String resolveModel(Prefs prefs, Conversation conversation, Provider provider) {
        if (conversation.model != null && conversation.model.length() > 0) {
            return conversation.model;
        }
        String override = prefs.modelOverride(provider.id());
        return override != null ? override : provider.defaultModel();
    }

    static ChatRequest buildRequest(Prefs prefs, Conversation conversation, Provider provider,
                                    String model, List<Message> history) {
        ChatRequest r = new ChatRequest();
        r.model = model;
        r.maxTokens = prefs.maxTokens();

        if (conversation.systemPrompt != null && conversation.systemPrompt.length() > 0) {
            r.system = conversation.systemPrompt;
        } else {
            String global = prefs.systemPrompt();
            r.system = global.length() > 0 ? global : null;
        }

        for (int i = 0; i < history.size(); i++) {
            Message m = history.get(i);
            // Error notices are ours, not the model's. Sending one back as an
            // assistant turn would poison the conversation.
            if (m.isError()) {
                continue;
            }
            if (Message.ROLE_USER.equals(m.role)) {
                r.messages.add(ChatMessage.user(m.content));
            } else if (Message.ROLE_ASSISTANT.equals(m.role)) {
                r.messages.add(ChatMessage.assistant(m.content));
            }
        }

        // Anthropic rejects temperature with a 400 on every current model, so
        // it only goes out when the user has explicitly opted in for an older
        // model.
        boolean wantsTemperature = prefs.temperature(provider.id()) != null;
        if (wantsTemperature) {
            if (provider.supportsTemperature()
                    || com.oneai.provider.AnthropicProvider.ID.equals(provider.id())
                        && prefs.sendSamplingParams()) {
                r.temperature = prefs.temperature(provider.id());
            }
        }

        r.effort = prefs.effort(provider.id());
        return r;
    }
}
