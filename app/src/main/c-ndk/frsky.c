//
// Created by Constantin on 01.11.2016.
//

#include "frsky.h"

#include <stdint.h>
#include <android/log.h>
#include "telemetry.h"

int ret_consti=0;

int frsky_parse_buffer(frsky_state_t *state, telemetry_data_t *td, uint8_t *buf, int buflen) {
    ret_consti=0;
	int new_data = 0;
	int i;
	for(i=0; i<buflen; ++i) {
		uint8_t ch = buf[i];
		switch(state->sm_state) {
			case 0:
				if(ch == 0x5e)
					state->sm_state = 1;
				break;
			case 1:
				if(ch == 0x5e)
					state->sm_state = 2;
				else
					state->sm_state = 0;
				break;
			case 2:
				if(ch == 0x5e) {
					state->pkg_pos = 0;
					new_data = new_data | frsky_interpret_packet(state, td);
                    ret_consti=1;
				}
				else {
					if(state->pkg_pos >= sizeof(state->pkg)) {
						state->pkg_pos = 0;
						state->sm_state = 0;
					} else {
						state->pkg[state->pkg_pos] = ch;
						state->pkg_pos++;
					}
				}
				break;
			default:
				state->sm_state = 0;
			break;
		}
	}
	//return new_data;
    return ret_consti;
}

int frsky_interpret_packet(frsky_state_t *state, telemetry_data_t *td) {
	uint16_t data;
	int new_data = 1;

	data = *(uint16_t*)(state->pkg+1);
	switch(state->pkg[0]) {
		case ID_VOLTAGE_AMP:
		{
			//uint16_t val = (state->pkg[2] >> 8) | ((state->pkg[1] & 0xf) << 8);
			//float battery = 3.0f * val / 500.0f;
			//td->voltage = battery;
			td->voltage = data / 10.0f;
		}
			break;
		case ID_ALTITUDE_BP:
			td->baro_altitude = data;
			break;
		case ID_ALTITUDE_AP:
			//td->baro_altitude += data/100;
			break;
		case ID_GPS_ALTIDUTE_BP:
			td->altitude = data;
			break;
		case ID_LONGITUDE_BP:
			td->longitude = data / 100;
			td->longitude += 1.0 * (data - td->longitude * 100) / 60;
			break;
		case ID_LONGITUDE_AP:
			td->longitude +=  1.0 * data / 60 / 10000;
			break;
		case ID_LATITUDE_BP:
			td->latitude = data / 100;
			td->latitude += 1.0 * (data - td->latitude * 100) / 60;
			break;
		case ID_LATITUDE_AP:
			td->latitude +=  1.0 * data / 60 / 10000;
			break;
		case ID_COURSE_BP:
			td->yaw = data;
			break;
		case ID_GPS_SPEED_BP:
			td->speed = 1.0 * data / 0.0194384449;
			break;
		case ID_GPS_SPEED_AP:
			td->speed += 1.0 * data / 1.94384449; //now we are in cm/s
			td->speed = td->speed / 100 / 1000 * 3600; //now we are in km/h
			break;
		case ID_ACC_X:
			td->pitch = data;
			break;
		case ID_ACC_Y:
			td->yaw = data;
			break;
		case ID_ACC_Z:
			td->roll = data;
			break;
		case ID_E_W:
			td->ew = data;
			break;
		case ID_N_S:
			td->ns = data;
			break;
		default:
			new_data = 0;
		break;
	}
	return new_data;
}
