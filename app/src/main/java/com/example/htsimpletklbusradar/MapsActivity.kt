package com.example.htsimpletklbusradar

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.preference.PreferenceManager
import android.support.v4.app.ActivityCompat
import android.support.v7.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import org.json.JSONObject

class MapsActivity : AppCompatActivity(), OnMapReadyCallback, GoogleMap.OnMarkerClickListener {
    // Kartta markkerin likkauksen disablointi
    override fun onMarkerClick(p0: Marker?) = false

    // google mappi olion alustus
    private lateinit var mMap: GoogleMap

    // Location stuff
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var lastLocation: Location
    private lateinit var locationCallback: LocationCallback

    private lateinit var locationRequest: LocationRequest
    private var locationUpdateState = false

    // TKL RESTAPI URL
    private val url = "http://data.itsfactory.fi/siriaccess/vm/json"

    // Bussien sijainti datataulu
    private var data = ArrayList<HashMap<String, String>>()

    // Thread stuff
    private lateinit var mHandler: Handler
    private lateinit var mTklData: Runnable


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_maps)

        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(p0: LocationResult) {
                super.onLocationResult(p0)

                lastLocation = p0.lastLocation
            }
        }

        createLocationRequest()

        val SP = PreferenceManager.getDefaultSharedPreferences(baseContext)

        if (savedInstanceState != null){
            data = savedInstanceState!!.getSerializable("DATA") as ArrayList<HashMap<String, String>>
        }

        mHandler = Handler()

        //tkl datan haku ja bussien merkkaus mäppiin
        mTklData = Runnable {
            getTKLData()
            mHandler.postDelayed(mTklData, (2 * 1000))
        }
        mHandler.postDelayed(mTklData,(2 * 1000))
    }

    public override fun onSaveInstanceState(savedInstanceState: Bundle) {
        savedInstanceState.putSerializable("DATA", data)

        super.onSaveInstanceState(savedInstanceState)
    }

    private fun startLocationUpdates() {

        if (ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE)
            return
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null /* Looper */)
    }

    private fun createLocationRequest() {
        locationRequest = LocationRequest()

        locationRequest.interval = 10000

        locationRequest.fastestInterval = 5000
        locationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY

        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)

        val client = LocationServices.getSettingsClient(this)
        val task = client.checkLocationSettings(builder.build())

        task.addOnSuccessListener {
            locationUpdateState = true
            startLocationUpdates()
        }
        task.addOnFailureListener { e ->
            if (e is ResolvableApiException) {
                // Location settings are not satisfied, but this can be fixed
                // by showing the user a dialog.
                try {
                    // Show the dialog by calling startResolutionForResult(),
                    // and check the result in onActivityResult().
                    e.startResolutionForResult(this@MapsActivity,
                        REQUEST_CHECK_SETTINGS)
                } catch (sendEx: IntentSender.SendIntentException) {
                    // Ignore the error.
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CHECK_SETTINGS) {
            if (resultCode == Activity.RESULT_OK) {
                locationUpdateState = true
                startLocationUpdates()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    public override fun onResume() {
        super.onResume()
        if (!locationUpdateState) {
            startLocationUpdates()
        }
    }

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera. In this case,
     * we just add a marker near Sydney, Australia.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap

        mMap.uiSettings.isZoomControlsEnabled = true
        mMap.setOnMarkerClickListener(this)

        // lataa tallenetut kordinaatit
        val SP = PreferenceManager.getDefaultSharedPreferences(baseContext)
        if (!data.isEmpty()) {
            for (i in data) {
                var line = i["line"]!!
                var cords = LatLng(i["latitude"]!!.toDouble(), i["longitude"]!!.toDouble())
                var bearing = i["bearing"]!!.toFloat()

                if (SP.getBoolean(("switch_pref_" + line.replace(Regex("\\D"), "")), true)) {
                    mMap?.addMarker(
                        MarkerOptions()
                            .position(cords)
                            .icon(BitmapDescriptorFactory.fromResource(busIcon(line)))
                            .rotation(bearing.toFloat())
                            .flat(true)
                    )
                }
            }
        }

        setUpMap()
    }

    // Kartan sisällön asetus
    private fun setUpMap() {
        if (ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE)
            return
        }

        mMap.isMyLocationEnabled = true
        mMap.uiSettings.isMyLocationButtonEnabled = true

        fusedLocationClient.lastLocation.addOnSuccessListener(this) { location ->
            if(location != null) {
                lastLocation = location
                val currentLatLng = LatLng(location.latitude, location.longitude)
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 12f))
            }
        }
    }

    // TKL RESTin datan haku
    private fun getTKLData(){

        if (ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.INTERNET), 1)
        }

        val queue = Volley.newRequestQueue(this)
        val jsonObjectRequest = JsonObjectRequest(
            Request.Method.GET, url, null,
            Response.Listener { response ->
                parseDataFromJsonObject(response)
            },
            Response.ErrorListener {error ->
                println(error)
            })
        queue.add(jsonObjectRequest)
    }

    // TKL datan parseaminen ja bussi maerkerin tekeminen
    private fun parseDataFromJsonObject(TKLData: JSONObject){
        val dataList = TKLData.getJSONObject( "Siri" ).getJSONObject("ServiceDelivery").
            getJSONArray("VehicleMonitoringDelivery").getJSONObject(0).
            getJSONArray("VehicleActivity")

        val SP = PreferenceManager.getDefaultSharedPreferences(baseContext)

        mMap.clear()

        for (i in 0..(dataList.length() - 1)){
            // linjan nro
            val line = dataList.getJSONObject(i).
                getJSONObject("MonitoredVehicleJourney").
                getJSONObject("LineRef").getString("value")

            // bussin longitude
            val longitude = dataList.getJSONObject(i).
                getJSONObject("MonitoredVehicleJourney").
                getJSONObject("VehicleLocation").getString("Longitude")

            // bussin latitude
            val latitude = dataList.getJSONObject(i).
                getJSONObject("MonitoredVehicleJourney").
                getJSONObject("VehicleLocation").getString("Latitude")

            // bussin kulkusuunta
            val bearing = dataList.getJSONObject(i).
                getJSONObject("MonitoredVehicleJourney").getString("Bearing")

            // luodaan LatLng objekti
            var cords = LatLng(latitude.toDouble(), longitude.toDouble())

            // bussi markkerin asetus mäppiin
            if (SP.getBoolean(("switch_pref_" + line.replace(Regex("\\D"),"")),true)){
                mMap?.addMarker(MarkerOptions()
                    .position(cords)
                    .icon(BitmapDescriptorFactory.fromResource(busIcon(line)))
                    .rotation(bearing.toFloat())
                    .flat(true))
            }

            data.clear()    // arrayn tyhjennys - emme tarvitse vanhaa dataa

            // datan tallenus
            val TKLItemHash = HashMap<String, String>()
            TKLItemHash.put("line", line)
            TKLItemHash.put("longitude", longitude)
            TKLItemHash.put("latitude", latitude)
            TKLItemHash.put("bearing", bearing)

            data.add(TKLItemHash) // uuden datan lisäys arrayhin
        }
    }

    // bussille oikean iconin valitseminen - pitäisi tehdä bitmapin ja canvas avulla...
    private fun busIcon(line: String):Int{
        var ico = when(line.replace(Regex("\\D"),"")){
            "1" -> this.resources.getIdentifier("drawable/line1",null,this.packageName)
            "2" -> this.resources.getIdentifier("drawable/line2",null,this.packageName)
            "3" -> this.resources.getIdentifier("drawable/line3",null,this.packageName)
            "4" -> this.resources.getIdentifier("drawable/line4",null,this.packageName)
            "5" -> this.resources.getIdentifier("drawable/line5",null,this.packageName)
            "6" -> this.resources.getIdentifier("drawable/line6",null,this.packageName)
            "8" -> this.resources.getIdentifier("drawable/line8",null,this.packageName)
            "9" -> this.resources.getIdentifier("drawable/line9",null,this.packageName)
            "10" -> this.resources.getIdentifier("drawable/line10",null,this.packageName)
            "11" -> this.resources.getIdentifier("drawable/line11",null,this.packageName)
            "12" -> this.resources.getIdentifier("drawable/line12",null,this.packageName)
            "14" -> this.resources.getIdentifier("drawable/line14",null,this.packageName)
            "15" -> this.resources.getIdentifier("drawable/line15",null,this.packageName)
            "17" -> this.resources.getIdentifier("drawable/line17",null,this.packageName)
            "20" -> this.resources.getIdentifier("drawable/line20",null,this.packageName)
            "21" -> this.resources.getIdentifier("drawable/line21",null,this.packageName)
            "24" -> this.resources.getIdentifier("drawable/line24",null,this.packageName)
            "25" -> this.resources.getIdentifier("drawable/line25",null,this.packageName)
            "26" -> this.resources.getIdentifier("drawable/line26",null,this.packageName)
            "27" -> this.resources.getIdentifier("drawable/line27",null,this.packageName)
            "28" -> this.resources.getIdentifier("drawable/line28",null,this.packageName)
            "29" -> this.resources.getIdentifier("drawable/line29",null,this.packageName)
            "31" -> this.resources.getIdentifier("drawable/line31",null,this.packageName)
            "32" -> this.resources.getIdentifier("drawable/line32",null,this.packageName)
            "33" -> this.resources.getIdentifier("drawable/line33",null,this.packageName)
            "35" -> this.resources.getIdentifier("drawable/line35",null,this.packageName)
            "37" -> this.resources.getIdentifier("drawable/line37",null,this.packageName)
            "38" -> this.resources.getIdentifier("drawable/line38",null,this.packageName)
            "40" -> this.resources.getIdentifier("drawable/line40",null,this.packageName)
            "41" -> this.resources.getIdentifier("drawable/line41",null,this.packageName)
            "42" -> this.resources.getIdentifier("drawable/line42",null,this.packageName)
            "43" -> this.resources.getIdentifier("drawable/line43",null,this.packageName)
            "44" -> this.resources.getIdentifier("drawable/line44",null,this.packageName)
            "45" -> this.resources.getIdentifier("drawable/line45",null,this.packageName)
            "46" -> this.resources.getIdentifier("drawable/line46",null,this.packageName)
            "49" -> this.resources.getIdentifier("drawable/line49",null,this.packageName)
            "50" -> this.resources.getIdentifier("drawable/line50",null,this.packageName)
            "51" -> this.resources.getIdentifier("drawable/line51",null,this.packageName)
            "53" -> this.resources.getIdentifier("drawable/line53",null,this.packageName)
            "55" -> this.resources.getIdentifier("drawable/line55",null,this.packageName)
            "56" -> this.resources.getIdentifier("drawable/line56",null,this.packageName)
            "57" -> this.resources.getIdentifier("drawable/line57",null,this.packageName)
            "58" -> this.resources.getIdentifier("drawable/line58",null,this.packageName)
            "63" -> this.resources.getIdentifier("drawable/line63",null,this.packageName)
            "65" -> this.resources.getIdentifier("drawable/line65",null,this.packageName)
            "70" -> this.resources.getIdentifier("drawable/line70",null,this.packageName)
            "71" -> this.resources.getIdentifier("drawable/line71",null,this.packageName)
            "72" -> this.resources.getIdentifier("drawable/line72",null,this.packageName)
            "73" -> this.resources.getIdentifier("drawable/line73",null,this.packageName)
            "74" -> this.resources.getIdentifier("drawable/line74",null,this.packageName)
            "77" -> this.resources.getIdentifier("drawable/line77",null,this.packageName)
            "78" -> this.resources.getIdentifier("drawable/line78",null,this.packageName)
            "79" -> this.resources.getIdentifier("drawable/line79",null,this.packageName)
            "80" -> this.resources.getIdentifier("drawable/line80",null,this.packageName)
            "81" -> this.resources.getIdentifier("drawable/line81",null,this.packageName)
            "83" -> this.resources.getIdentifier("drawable/line83",null,this.packageName)
            "84" -> this.resources.getIdentifier("drawable/line84",null,this.packageName)
            "85" -> this.resources.getIdentifier("drawable/line85",null,this.packageName)
            "86" -> this.resources.getIdentifier("drawable/line86",null,this.packageName)
            "87" -> this.resources.getIdentifier("drawable/line87",null,this.packageName)
            "90" -> this.resources.getIdentifier("drawable/line90",null,this.packageName)
            "91" -> this.resources.getIdentifier("drawable/line91",null,this.packageName)
            "92" -> this.resources.getIdentifier("drawable/line92",null,this.packageName)
            "95" -> this.resources.getIdentifier("drawable/line95",null,this.packageName)
            "115" -> this.resources.getIdentifier("drawable/line115",null,this.packageName)
            "137" -> this.resources.getIdentifier("drawable/line137",null,this.packageName)
            else -> this.resources.getIdentifier("drawable/dull",null,this.packageName)
        }

        return ico
    }

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
        private const val REQUEST_CHECK_SETTINGS = 2
    }

    // 3 pisteen valikon luonti
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu, menu)
        return true
    }

    // 3 pisteen valikon sisältö ja sisällön linkitys
    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        val id = item?.itemId

        if (id == R.id.settings){
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
            return true
        }

        return super.onOptionsItemSelected(item)
    }
}
