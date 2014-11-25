package pro.dbro.gameshow.ui.activities;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.apache.airavata.samples.LevenshteinDistanceService;

import java.util.List;

import butterknife.ButterKnife;
import butterknife.InjectView;
import butterknife.InjectViews;
import butterknife.OnClick;
import pro.dbro.gameshow.R;
import pro.dbro.gameshow.RecognitionAdapter;
import pro.dbro.gameshow.model.Question;


public class QuestionActivity extends Activity {
    public final String TAG = this.getClass().getSimpleName();

    public static String INTENT_ACTION = "pro.dbro.gameshow.QuestionResult";
    public static int ANSWERED_CORRECT = 1;
    public static int ANSWERED_INCORRECT = 0;

    private static int QUESTION_ANSWER_TIME_MS = 15 * 1000;

    private static enum State {WILL_SPEAK_ANSWER, SPEAKING_ANSWER, WILL_SELECT_ANSWER, SHOWING_ANSWER, OUT_OF_TIME}

    private Question question;
    private State state;

    @InjectView(R.id.prompt)
    TextView promptView;

    @InjectView(R.id.choiceContainer)
    ViewGroup choiceContainer;

    @InjectView(R.id.speakAnswer)
    Button speakAnswer;

    @InjectViews({R.id.choice1, R.id.choice2, R.id.choice3, R.id.choice4})
    List<Button> choiceViews;

    @InjectView(R.id.timerBar)
    ProgressBar timerBar;

    private static MediaPlayer sMediaPlayer;
    private CountDownTimer mCountdownTimer;
    private SpeechRecognizer mSpeechRecognizer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_question);
        ButterKnife.inject(this);

        question = (Question) getIntent().getExtras().getSerializable("question");
        final Typeface promptTypeface = Typeface.createFromAsset(getAssets(), "fonts/Korinna_Bold.ttf");
        promptView.setTypeface(promptTypeface);
        promptView.setText(question.prompt.toUpperCase());
        if (question.choices.size() > 1) {
            state = State.WILL_SELECT_ANSWER;
            for (int x = 0; x < question.choices.size(); x++) {
                choiceViews.get(x).setText(question.getAnswer());
                choiceViews.get(x).setTag(x);
            }
        } else {
            state = State.WILL_SPEAK_ANSWER;
            choiceContainer.setVisibility(View.GONE);
            speakAnswer.setVisibility(View.VISIBLE);
        }
        startTimer();
        if (sMediaPlayer == null) {
            sMediaPlayer = MediaPlayer.create(this, R.raw.out_of_time);
            sMediaPlayer.setVolume(.7f, .7f);
        }
    }

    private void startSpeechRecognizer() {
        mSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        mSpeechRecognizer.setRecognitionListener(new RecognitionAdapter() {

            @Override
            public void onBeginningOfSpeech() {
                Log.i(TAG, "onBeginningOfSpeech");
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onResults(Bundle results) {
                StringBuilder builder = new StringBuilder();

                List<String> recognitionResults = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

                for (String result : recognitionResults) {
                    builder.append(result);
                    builder.append(", ");
                }
                Log.i(TAG, "Got results: " + builder.toString());

                handleSpokenAnswer(recognitionResults.size() == 0 ? "?" : recognitionResults.get(0), question.getAnswer());
            }

            @Override
            public void onError(int error) {
                Log.w(TAG, "Speech recognition error: " + error);
                handleSpokenAnswer("?", question.getAnswer());
            }
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, "voice.recognition.test");

        mSpeechRecognizer.startListening(intent);
    }

    private void stopSpeechRecognizer() {
        if (mSpeechRecognizer == null) return;

        mSpeechRecognizer.stopListening();
        mSpeechRecognizer.destroy();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (sMediaPlayer != null) {
            sMediaPlayer.release();
            sMediaPlayer = null;
        }
        stopSpeechRecognizer();
    }

    private void startTimer() {
        ObjectAnimator animation = ObjectAnimator.ofInt(timerBar, "progress", 100, 0);
        animation.setInterpolator(new LinearInterpolator());
        animation.setDuration(QUESTION_ANSWER_TIME_MS);
        animation.start();
        mCountdownTimer = new CountDownTimer(QUESTION_ANSWER_TIME_MS, QUESTION_ANSWER_TIME_MS) {

            public void onTick(long millisUntilFinished) {
            }

            public void onFinish() {
                if (state == State.WILL_SELECT_ANSWER || state == State.WILL_SPEAK_ANSWER) {
                    if (sMediaPlayer != null) sMediaPlayer.start();
                    promptView.setText(question.getAnswer());
                    state = State.OUT_OF_TIME;
                    choiceContainer.setVisibility(View.GONE);
                    speakAnswer.setVisibility(View.VISIBLE);
                    speakAnswer.setText(getString(R.string.continue_on));
                }
            }
        }.start();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true;
        }
        return false;
    }

    @OnClick(R.id.speakAnswer)
    public void onSpeakAnswerClicked(View showAnswer) {
        if (state == State.OUT_OF_TIME) {
            finishWithQuestionResult(false);
            return;
        }
        if (state == State.SPEAKING_ANSWER) return;

        timerBar.setVisibility(View.INVISIBLE);
        state = State.SPEAKING_ANSWER;
        speakAnswer.setText(getString(R.string.listening));
        startSpeechRecognizer();
    }

    private static final String[] IGNORED_REDUCED_PREFIXES = new String[] {"whatis", "what's", "whois", "who's"};

    private void handleSpokenAnswer(String spokenAnswer, String correctAnswer) {
        // Remove all case, whitespace, and non alphabetical characters
        String reducedCorrectAnswer = correctAnswer.toLowerCase()
                                                   .replaceAll("\\s+", "")
                                                   .replaceAll("[^a-zA-Z ]", "");

        String reducedSpokenAnswer = spokenAnswer.toLowerCase()
                                                 .replaceAll("\\s+", "")
                                                 .replaceAll("[^a-zA-Z ]", "");

        for (String prefix : IGNORED_REDUCED_PREFIXES) {
            if (reducedSpokenAnswer.startsWith(prefix))
                reducedSpokenAnswer = reducedSpokenAnswer.replaceFirst(prefix, "");
        }

        LevenshteinDistanceService distanceService = new LevenshteinDistanceService();
        int distance = distanceService.computeDistance(reducedSpokenAnswer, reducedCorrectAnswer);
        int maxDistance = Math.max(reducedSpokenAnswer.length(), reducedCorrectAnswer.length());

        float match = (maxDistance - distance) / (float) maxDistance;
        Log.i(TAG, String.format("(%d - %d) / %d = %f", maxDistance, distance, maxDistance, match));

        Log.i(TAG, String.format("distance: '%s' - '%s' is %d", reducedSpokenAnswer, reducedCorrectAnswer, distance));
        Log.i(TAG, String.format("Match: '%s' - '%s' is %f", spokenAnswer, correctAnswer, match));

        if (match > .7) {
            finishWithQuestionResult(true);
        } else {
            promptView.setText(String.format("Heard: %s \nAnswer: %s",
                                             spokenAnswer.replace("what is", "")
                                                         .replace("what's", "")
                                                         .replace("who is", "")
                                                         .replace("who's", ""),
                                             correctAnswer));

            choiceContainer.setVisibility(View.VISIBLE);
            speakAnswer.setVisibility(View.GONE);
            choiceViews.get(0).setVisibility(View.INVISIBLE);
            choiceViews.get(1).setText("I'm Right");
            choiceViews.get(1).setTag(true);
            choiceViews.get(2).setText("I'm Wrong");
            choiceViews.get(2).setTag(false);
            choiceViews.get(2).requestFocus();
            choiceViews.get(3).setVisibility(View.INVISIBLE);
            state = State.SHOWING_ANSWER;
        }
    }

    @OnClick({R.id.choice1, R.id.choice2, R.id.choice3, R.id.choice4})
    public void onAnswerSelected(View selectedAnswerView) {
        switch (state) {
            case OUT_OF_TIME:
                finishWithQuestionResult(false);
                break;
            case WILL_SELECT_ANSWER:
                timerBar.setVisibility(View.INVISIBLE);
                int selection = (int) selectedAnswerView.getTag();
                finishWithQuestionResult(selection == question.correctChoice);
                break;
            case SHOWING_ANSWER:
                boolean answeredCorrectly = (boolean) selectedAnswerView.getTag();
                finishWithQuestionResult(answeredCorrectly);
                break;
        }
    }

    private void finishWithQuestionResult(boolean correctAnswer) {
        Intent result = new Intent(INTENT_ACTION);
        setResult((correctAnswer ? ANSWERED_CORRECT : ANSWERED_INCORRECT), result);
        finish();
    }

    @Override
    public void onPause() {
        super.onPause();
        mCountdownTimer.cancel();
    }
}
