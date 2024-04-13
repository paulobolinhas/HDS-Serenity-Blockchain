package pt.ulisboa.tecnico.hdsledger.utilities;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.subjects.PublishSubject;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

public class RoundChangeTracker {
    private static long expirationTimeSeconds = 3;
    private boolean isRunning;
    private Instant expirationTime;
    private PublishSubject<Integer> expirationSubject;
    private Disposable timerDisposable;



    /**
     * Once the message is created, we begin "waiting" for a response; if it is not received until the validity expires,
     * expirationSubject will be triggered.
     */
    public RoundChangeTracker(long expirationTime) {
        expirationTimeSeconds = expirationTime;
        this.isRunning = false;
        this.expirationTime = null;
        this.expirationSubject = PublishSubject.create();
    }



    public RoundChangeTracker startTimerNode(int roundTracked) {
        return this.startTimer(roundTracked);
    }

    public RoundChangeTracker startTimerClient(int messageIdTracked) {
        return this.startTimer(messageIdTracked);
    }

    private RoundChangeTracker startTimer(int contentTracked) {
        System.out.println("TIMER iniciado com tempo " + expirationTimeSeconds);
        this.isRunning = true;
        timerDisposable = Observable.timer(expirationTimeSeconds, TimeUnit.SECONDS)
                .subscribe(ignore -> {
                    if (isRunning) {
                        stopTimer();
                        expirationSubject.onNext(contentTracked);
                    }
                });
        return this;
    }


    private void stopTimer() {
        this.isRunning = false;
        this.expirationTime = null;
    }

    public void cancelTracker() {
        if (!timerDisposable.isDisposed() && timerDisposable != null) {
            timerDisposable.dispose();
            stopTimer();
        }
    }

    public boolean isRunning() {
        return isRunning;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expirationTime);
    }

    public Observable<Integer> getExpirationObservable() {
        return expirationSubject;
    }
}
