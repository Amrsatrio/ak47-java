package com.tb24.discordbot;

import com.google.gson.JsonSyntaxException;
import com.tb24.fn.EpicApi;
import com.tb24.fn.model.EpicError;
import okhttp3.Response;

import java.io.IOException;

public class HttpException extends RuntimeException {
	public okhttp3.Response response;
	public String responseStr;
	public EpicError epicError;

	public HttpException(Response response) {
		this.response = response;
		try {
			responseStr = response.body().string(); // remember this method consumes the response body so it should be executed only once
			epicError = toEpicError(this, EpicError.class);
		} catch (IOException ignored) {
		}
	}

	public HttpException(retrofit2.Response<?> response) {
		this.response = response.raw();
		try {
			responseStr = response.errorBody().string();
			epicError = toEpicError(this, EpicError.class);
		} catch (IOException ignored) {
		}
	}

	public int code() {
		return response.code();
	}

	@Override
	public String toString() {
		return super.toString() + ": " + (epicError != null ? epicError.getDisplayText() : responseStr);
	}

	public static <T extends EpicError> EpicError toEpicError(HttpException ex, Class<T> toErrorClass) {
		try {
			//Log2.e("EpicError", ex.responseStr);
			return EpicApi.GSON.fromJson(ex.responseStr, toErrorClass);
		} catch (JsonSyntaxException e) {
			EpicError epicError = new EpicError();
			epicError.errorMessage = "An error occurred while communicating with the game servers:" + '\n' + String.format("HTTP %d response from %s", ex.code(), DiscordBot.ENV.equals("prod") ? "<REDACTED>" : ex.response.request().url());
			return epicError;
		}
	}
}
