package com.futbolix.reservations.application;

/** A service this one needs did not answer in time: 503, never a guess. */
public class DependencyUnavailableException extends RuntimeException {

    private final String dependency;

    public DependencyUnavailableException(String dependency, Throwable cause) {
        super(dependency + " is unavailable", cause);
        this.dependency = dependency;
    }

    public String dependency() {
        return dependency;
    }
}
