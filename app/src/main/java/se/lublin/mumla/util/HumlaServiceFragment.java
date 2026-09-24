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

package se.lublin.mumla.util;

import android.app.Activity;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LifecycleOwnerKt;

import java.util.concurrent.CancellationException;

import kotlinx.coroutines.Job;

import se.lublin.humla.IHumlaService;
import se.lublin.humla.session.HumlaEvent;
import se.lublin.mumla.service.IMumlaService;

/** Fragment class intended to make binding the Humla service to fragments easier. */
public abstract class HumlaServiceFragment extends Fragment {

    private HumlaServiceProvider mServiceProvider;

    /** State boolean to make sure we don't double initialize a fragment once a service has been bound. */
    private boolean mBound;

    /** Collects the bound service's events into {@link #onServiceEvent}. */
    @Nullable
    private Job mEvents;

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        try {
            mServiceProvider = (HumlaServiceProvider) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString() + " must implement HumlaServiceProvider");
        }
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mServiceProvider.addServiceFragment(this);
        if(mServiceProvider.getService() != null && !mBound)
            onServiceAttached(mServiceProvider.getService());
    }

    @Override
    public void onDestroy() {
        mServiceProvider.removeServiceFragment(this);
        if(mServiceProvider.getService() != null && mBound)
            onServiceDetached(mServiceProvider.getService());
        super.onDestroy();
    }

    /** Initializes the fragment from the service; called once per bind, whichever loads first. */
    public void onServiceBound(IHumlaService service) { }

    public void onServiceUnbound() { }

    /** Called on the main thread for each session event while the service is bound. */
    public void onServiceEvent(HumlaEvent event) { }

    private void onServiceAttached(IHumlaService service) {
        mBound = true;
        mEvents = HumlaEvents.collectEvents(LifecycleOwnerKt.getLifecycleScope(this), service, this::onServiceEvent);
        onServiceBound(service);
    }

    private void onServiceDetached(IHumlaService service) {
        mBound = false;
        if (mEvents != null) {
            mEvents.cancel((CancellationException) null);
            mEvents = null;
        }
        onServiceUnbound();
    }

    public void setServiceBound(boolean bound) {
        if(bound && !mBound)
            onServiceAttached(mServiceProvider.getService());
        else if(mBound && !bound)
            onServiceDetached(mServiceProvider.getService());
    }

    public IMumlaService getService() {
        return mServiceProvider.getService();
    }
}
