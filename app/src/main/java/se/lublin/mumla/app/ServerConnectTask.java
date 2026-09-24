/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.AsyncTask;
import android.os.IBinder;

import se.lublin.humla.model.Server;
import se.lublin.humla.session.SessionConfig;
import se.lublin.mumla.Settings;
import se.lublin.mumla.db.MumlaDatabase;
import se.lublin.mumla.service.IMumlaService;
import se.lublin.mumla.service.MumlaService;
import se.lublin.mumla.service.SessionSettings;

/**
 * Builds the session configuration for a server off the main thread, then starts MumlaService
 * and hands it the configuration through the binder.
 */
public class ServerConnectTask extends AsyncTask<Server, Void, SessionConfig> {
    private final Context mContext;
    private final MumlaDatabase mDatabase;
    private final Settings mSettings;

    public ServerConnectTask(Context context, MumlaDatabase database) {
        // The application context: the bind below may outlive the activity that started it.
        mContext = context.getApplicationContext();
        mDatabase = database;
        mSettings = Settings.getInstance(context);
    }

    @Override
    protected SessionConfig doInBackground(Server... params) {
        return SessionSettings.forServer(mContext, mSettings, mDatabase, params[0]);
    }

    @Override
    protected void onPostExecute(SessionConfig config) {
        super.onPostExecute(config);
        Intent intent = new Intent(mContext, MumlaService.class);
        // Started, not only bound, so the session outlives every client.
        mContext.startService(intent);
        mContext.bindService(intent, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                IMumlaService service = ((MumlaService.MumlaBinder) binder).getService();
                service.configure(config);
                service.connect();
                mContext.unbindService(this);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        }, Context.BIND_AUTO_CREATE);
    }
}
