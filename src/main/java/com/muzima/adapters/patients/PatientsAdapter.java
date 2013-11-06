package com.muzima.adapters.patients;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import com.muzima.MuzimaApplication;
import com.muzima.R;
import com.muzima.adapters.ListAdapter;
import com.muzima.api.model.Patient;
import com.muzima.controller.PatientController;
import com.muzima.domain.Credentials;

import java.util.ArrayList;
import java.util.List;

import static com.muzima.utils.Constants.DataSyncServiceConstants.SyncStatusConstants.AUTHENTICATION_SUCCESS;
import static com.muzima.utils.Constants.LOCAL_SEARCH_MODE;
import static com.muzima.utils.Constants.PATIENT_SEARCH_PREF;
import static com.muzima.utils.Constants.PATIENT_SEARCH_PREF_KEY;
import static com.muzima.utils.Constants.SERVER_SEARCH_MODE;
import static com.muzima.utils.DateUtils.getFormattedDate;

public class PatientsAdapter extends ListAdapter<Patient> {
    private static final String TAG = "PatientsAdapter";
    public static final String SEARCH = "search";
    private PatientController patientController;
    private final String cohortId;
    protected BackgroundListQueryTaskListener backgroundListQueryTaskListener;

    public PatientsAdapter(Context context, int textViewResourceId, PatientController patientController, String cohortId) {
        super(context, textViewResourceId);
        this.patientController = patientController;
        this.cohortId = cohortId;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            LayoutInflater layoutInflater = LayoutInflater.from(getContext());
            convertView = layoutInflater.inflate(
                    R.layout.item_patients_list, parent, false);
            holder = new ViewHolder();
            holder.genderImg = (ImageView) convertView.findViewById(R.id.genderImg);
            holder.name = (TextView) convertView.findViewById(R.id.name);
            holder.dateOfBirth = (TextView) convertView.findViewById(R.id.dateOfBirth);
            holder.identifier = (TextView) convertView.findViewById(R.id.identifier);
            convertView.setTag(holder);
        }

        holder = (ViewHolder) convertView.getTag();

        Patient patient = getItem(position);

        holder.dateOfBirth.setText("DOB: " + getFormattedDate(patient.getBirthdate()));
        holder.identifier.setText(patient.getIdentifier());
        holder.name.setText(getPatientFullName(patient));
        holder.genderImg.setImageResource(getGenderImage(patient.getGender()));
        return convertView;
    }

    private String getPatientFullName(Patient patient) {
        return patient.getFamilyName() + ", " + patient.getGivenName() + " " + patient.getMiddleName();
    }

    private int getGenderImage(String gender) {
        int imgSrc = gender.equalsIgnoreCase("M") ? R.drawable.ic_male : R.drawable.ic_female;
        return imgSrc;
    }

    @Override
    public void reloadData() {
        new BackgroundQueryTask().execute(cohortId);
    }

    public void searchPatientOnServer(String searchString) {
        new ServerSearchBackgroundTask().execute(searchString);
    }

    public void search(String text) {
        new BackgroundQueryTask().execute(text, SEARCH);
    }

    public BackgroundListQueryTaskListener getBackgroundListQueryTaskListener() {
        return backgroundListQueryTaskListener;
    }

    public void setBackgroundListQueryTaskListener(BackgroundListQueryTaskListener backgroundListQueryTaskListener) {
        this.backgroundListQueryTaskListener = backgroundListQueryTaskListener;
    }

    private class ServerSearchBackgroundTask extends AsyncTask<String, Void, List<Patient>> {
        long startingTime;

        @Override
        protected void onPreExecute() {
            startingTime = preExecuteSearch();
        }

        @Override
        protected void onPostExecute(List<Patient> patients) {
            postExecuteSearch(patients, startingTime);
        }

        @Override
        protected List<Patient> doInBackground(String... strings) {
            MuzimaApplication applicationContext = (MuzimaApplication) getContext();
            addSearchModeToSharedPref(SERVER_SEARCH_MODE);

            Credentials credentials = new Credentials(getContext());
            try {
                int authenticateResult = applicationContext.getMuzimaSyncService().authenticate(credentials.getCredentialsArray());
                if (authenticateResult == AUTHENTICATION_SUCCESS) {
                    return patientController.searchPatientOnServer(strings[0]);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error while searching for patient in the server : " + t);
            } finally {
                applicationContext.getMuzimaContext().closeSession();
            }
            Log.e(TAG, "Authentication failure !! Returning empty patient list");
            return new ArrayList<Patient>();
        }
    }

    private class BackgroundQueryTask extends AsyncTask<String, Void, List<Patient>> {
        long mStartingTime;

        @Override
        protected void onPreExecute() {
            mStartingTime = preExecuteSearch();
        }


        @Override
        protected List<Patient> doInBackground(String... params) {
            if (isSearch(params)) {
                try {
                    addSearchModeToSharedPref(LOCAL_SEARCH_MODE);
                    return patientController.searchPatientLocally(params[0], cohortId);
                } catch (PatientController.PatientLoadException e) {
                    Log.w(TAG, "Exception occurred while searching patients for " + params[0] + " search string. " + e);
                }
            }
            List<Patient> patients = null;
            String cohortUuid = params[0];
            try {
                if (cohortUuid != null) {
                    patients = patientController.getPatients(cohortUuid);
                    Log.i(TAG, "#Patients in the cohort " + cohortUuid + ": " + patients.size());
                } else {
                    patients = patientController.getAllPatients();
                    Log.i(TAG, "#All patients: " + patients.size());
                }

            } catch (PatientController.PatientLoadException e) {
                Log.w(TAG, "Exception occurred while fetching patients" + e);
            }
            return patients;
        }

        private boolean isSearch(String[] params) {
            return params.length == 2 && SEARCH.equals(params[1]);
        }

        @Override
        protected void onPostExecute(List<Patient> patients) {
            postExecuteSearch(patients, mStartingTime);
        }
    }

    private long preExecuteSearch() {
        if (backgroundListQueryTaskListener != null) {
            backgroundListQueryTaskListener.onQueryTaskStarted();
        }
        return System.currentTimeMillis();

    }

    private void postExecuteSearch(List<Patient> patients, long startingTime) {
        if (patients == null) {
            Toast.makeText(getContext(), "Something went wrong while fetching patients from local repo", Toast.LENGTH_SHORT).show();
            return;
        }

        PatientsAdapter.this.clear();

        for (Patient patient : patients) {
            add(patient);
        }
        notifyDataSetChanged();

        long currentTime = System.currentTimeMillis();
        Log.d(TAG, "Time taken in fetching patients from local repo: " + (currentTime - startingTime) / 1000 + " sec");

        if (backgroundListQueryTaskListener != null) {
            backgroundListQueryTaskListener.onQueryTaskFinish();
        }
    }

    private void addSearchModeToSharedPref(String searchMode) {
        SharedPreferences sharedPreferences = getContext().getSharedPreferences(PATIENT_SEARCH_PREF, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(PATIENT_SEARCH_PREF_KEY, searchMode);
        editor.commit();
    }

    private class ViewHolder {
        ImageView genderImg;
        TextView name;
        TextView dateOfBirth;
        TextView identifier;
    }
}
